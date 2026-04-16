package com.crimdet.controller;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.*;
import com.crimdet.repository.DetectionLogRepository;
import com.crimdet.service.CriminalService;
import com.crimdet.service.FaceDetectionService;
import com.crimdet.service.FaceEmbeddingService;
import com.crimdet.service.FaceMatchingService;
import com.crimdet.service.WebcamService;
import com.crimdet.util.ImageUtils;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.Cursor;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class LiveMonitorController {

    private static final Logger log = LoggerFactory.getLogger(LiveMonitorController.class);
    private static final Duration MATCH_COOLDOWN = Duration.ofSeconds(30);

    private enum MonitorMode { IDLE, WEBCAM_STARTING, WEBCAM_RUNNING, IMAGE_LOADED, SCANNING }

    @FXML private StackPane imageContainer;
    @FXML private ImageView imageView;
    @FXML private Canvas overlayCanvas;
    @FXML private Label emptyImageLabel;
    @FXML private Button webcamBtn;
    @FXML private Button uploadBtn;
    @FXML private Button scanBtn;
    @FXML private Button clearBtn;
    @FXML private VBox resultsBox;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private Label progressLabel;

    private MainController mainController;
    private BufferedImage currentImage;
    private byte[] currentImageBytes;

    private CriminalService criminalService;
    private FaceEmbeddingService embeddingService;
    private FaceMatchingService matchingService;
    private DetectionLogRepository detectionLogRepo;

    // Webcam fields
    private WebcamService webcamService;
    private ExecutorService detectionExecutor;
    private WritableImage webcamImage;
    private final AtomicReference<BufferedImage> displayFrame = new AtomicReference<>();
    private final AtomicReference<BufferedImage> detectionFrame = new AtomicReference<>();
    private volatile boolean detectionBusy = false;
    private volatile boolean renderPending = false;
    private final Map<Long, Instant> matchCooldowns = new ConcurrentHashMap<>();
    private Set<Long> previousMatchIds = new HashSet<>();
    private MonitorMode mode = MonitorMode.IDLE;

    // Store scan results for overlay drawing
    private List<FaceResult> lastResults = new ArrayList<>();
    private volatile Thread activeScanThread;
    private boolean drawScheduled = false;

    private void ensureServicesInitialized() {
        if (criminalService == null) {
            criminalService = new CriminalService();
            embeddingService = new FaceEmbeddingService();
            matchingService = new FaceMatchingService();
            detectionLogRepo = new DetectionLogRepository(DatabaseConfig.getInstance().getJdbi());
        }
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Bind imageView fitWidth/fitHeight to container size
        imageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(48));
        imageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(48));

        // Redraw overlay when container resizes (debounced)
        overlayCanvas.setManaged(false);
        imageContainer.widthProperty().addListener((obs, o, n) -> scheduleDrawOverlay());
        imageContainer.heightProperty().addListener((obs, o, n) -> scheduleDrawOverlay());

        showEmptyState();
        updateMode(MonitorMode.IDLE);
    }

    private void scheduleDrawOverlay() {
        if (!drawScheduled) {
            drawScheduled = true;
            Platform.runLater(() -> {
                drawScheduled = false;
                drawOverlay();
            });
        }
    }

    // ── Webcam ──────────────────────────────────────────────────

    @FXML
    public void onToggleWebcam() {
        if (mode == MonitorMode.WEBCAM_RUNNING) {
            stopWebcam();
            updateMode(MonitorMode.IDLE);
            showEmptyState();
            return;
        }

        // Stop any image scan in progress
        if (activeScanThread != null && activeScanThread.isAlive()) {
            activeScanThread.interrupt();
        }

        currentImage = null;
        currentImageBytes = null;
        lastResults.clear();
        clearOverlay();
        showEmptyResults();

        updateMode(MonitorMode.WEBCAM_STARTING);
        emptyImageLabel.setText("Connecting to camera...");
        emptyImageLabel.setVisible(true);
        emptyImageLabel.setManaged(true);
        progressIndicator.setVisible(true);
        progressIndicator.setManaged(true);

        ensureServicesInitialized();

        if (webcamService == null) {
            webcamService = new WebcamService();
        }

        detectionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "detection-worker");
            t.setDaemon(true);
            return t;
        });

        matchingService.refreshCache();

        webcamService.start(this::onCameraFrame, this::onCameraStateChanged);
    }

    private void onCameraFrame(BufferedImage frame) {
        displayFrame.set(frame);
        detectionFrame.set(frame);

        // Display path (coalesced)
        if (!renderPending) {
            renderPending = true;
            Platform.runLater(() -> {
                renderPending = false;
                BufferedImage f = displayFrame.get();
                if (f == null) return;
                if (webcamImage == null || (int) webcamImage.getWidth() != f.getWidth()
                        || (int) webcamImage.getHeight() != f.getHeight()) {
                    webcamImage = new WritableImage(f.getWidth(), f.getHeight());
                }
                SwingFXUtils.toFXImage(f, webcamImage);
                imageView.setImage(webcamImage);
                emptyImageLabel.setVisible(false);
                emptyImageLabel.setManaged(false);
            });
        }

        // Detection path (throttled by detectionBusy)
        if (!detectionBusy && detectionExecutor != null && !detectionExecutor.isShutdown()) {
            detectionExecutor.submit(this::runDetectionCycle);
        }
    }

    private void onCameraStateChanged(WebcamService.State oldState, WebcamService.State newState, String errorMsg) {
        Platform.runLater(() -> {
            if (newState == WebcamService.State.RUNNING) {
                updateMode(MonitorMode.WEBCAM_RUNNING);
                progressIndicator.setVisible(false);
                progressIndicator.setManaged(false);
                emptyImageLabel.setVisible(false);
                emptyImageLabel.setManaged(false);
                statusLabel.setText("Webcam active — scanning for faces...");
            } else if (newState == WebcamService.State.ERROR) {
                stopWebcam();
                updateMode(MonitorMode.IDLE);
                String msg = errorMsg != null ? errorMsg
                        : "Camera access denied. Grant access in System Settings > Privacy & Security > Camera, then try again.";
                emptyImageLabel.setText(msg);
                emptyImageLabel.setVisible(true);
                emptyImageLabel.setManaged(true);
                progressIndicator.setVisible(false);
                progressIndicator.setManaged(false);
                statusLabel.setText("Camera error");
            }
        });
    }

    private void runDetectionCycle() {
        if (detectionBusy) return;
        detectionBusy = true;
        try {
            BufferedImage frame = detectionFrame.getAndSet(null);
            if (frame == null) return;

            long startMs = System.currentTimeMillis();
            List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(frame);
            List<FaceResult> results = new ArrayList<>();
            int matchCount = 0;

            for (DetectedFace face : faces) {
                Embedding embedding = embeddingService.extractEmbedding(face);
                // Null out originalImage to free 1080p frame memory
                face.setOriginalImage(null);

                List<MatchResult> matches = matchingService.findMatches(embedding);
                if (!matches.isEmpty()) {
                    MatchResult best = matches.get(0);
                    results.add(new FaceResult(face, best));
                    matchCount++;
                    logMatchWithDeduplication(best, face);
                } else {
                    results.add(new FaceResult(face, null));
                }
            }

            long durationMs = System.currentTimeMillis() - startMs;
            double fps = durationMs > 0 ? 1000.0 / durationMs : 0;

            final var finalResults = results;
            final int faceCount = faces.size();
            final int finalMatchCount = matchCount;

            // Only rebuild cards if match set changed
            Set<Long> currentMatchIds = results.stream()
                    .filter(r -> r.match() != null)
                    .map(r -> r.match().getCriminalId())
                    .collect(Collectors.toSet());
            boolean cardsChanged = !currentMatchIds.equals(previousMatchIds);
            previousMatchIds = currentMatchIds;

            Platform.runLater(() -> {
                lastResults = finalResults;
                drawOverlay();
                if (cardsChanged) buildResultCards(finalResults);
                statusLabel.setText(String.format("Faces: %d | Matches: %d | Detection: %.1f fps",
                        faceCount, finalMatchCount, fps));
            });
        } catch (Exception e) {
            log.error("Detection cycle failed", e);
        } finally {
            detectionBusy = false;
        }
    }

    private void logMatchWithDeduplication(MatchResult match, DetectedFace face) {
        long criminalId = match.getCriminalId();
        Instant now = Instant.now();
        Instant lastLogged = matchCooldowns.get(criminalId);
        if (lastLogged != null && Duration.between(lastLogged, now).compareTo(MATCH_COOLDOWN) < 0) {
            return;
        }
        matchCooldowns.put(criminalId, now);
        try {
            DetectionLog dl = new DetectionLog();
            dl.setCriminalId(criminalId);
            dl.setConfidence(match.getConfidence());
            dl.setScreenshot(ImageUtils.toBytes(face.getCroppedFace(), "png"));
            dl.setNotes("Webcam live detection: " + match.getCriminalName());
            detectionLogRepo.insert(dl);
        } catch (Exception e) {
            log.error("Failed to log detection", e);
        }
    }

    private void stopWebcam() {
        if (webcamService != null) webcamService.stop();
        if (detectionExecutor != null) {
            detectionExecutor.shutdownNow();
            try { detectionExecutor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            detectionExecutor = null;
        }
        webcamImage = null;
        displayFrame.set(null);
        detectionFrame.set(null);
        detectionBusy = false;
        matchCooldowns.clear();
        previousMatchIds.clear();
    }

    public void onScreenDeactivated() {
        if (mode == MonitorMode.WEBCAM_RUNNING || mode == MonitorMode.WEBCAM_STARTING) {
            stopWebcam();
            updateMode(MonitorMode.IDLE);
            showEmptyState();
        }
    }

    private void updateMode(MonitorMode newMode) {
        this.mode = newMode;
        switch (newMode) {
            case IDLE -> {
                webcamBtn.setText("Start Webcam");
                webcamBtn.setDisable(false);
                webcamBtn.getStyleClass().remove("webcam-active");
                if (!webcamBtn.getStyleClass().contains("accent")) webcamBtn.getStyleClass().add("accent");
                uploadBtn.setDisable(false);
                scanBtn.setDisable(true);
                clearBtn.setDisable(true);
            }
            case WEBCAM_STARTING -> {
                webcamBtn.setText("Starting...");
                webcamBtn.setDisable(true);
                uploadBtn.setDisable(true);
                scanBtn.setDisable(true);
                clearBtn.setDisable(true);
            }
            case WEBCAM_RUNNING -> {
                webcamBtn.setText("Stop Webcam");
                webcamBtn.setDisable(false);
                webcamBtn.getStyleClass().remove("accent");
                if (!webcamBtn.getStyleClass().contains("webcam-active")) webcamBtn.getStyleClass().add("webcam-active");
                uploadBtn.setDisable(true);
                scanBtn.setDisable(true);
                clearBtn.setDisable(true);
            }
            case IMAGE_LOADED -> {
                webcamBtn.setText("Start Webcam");
                webcamBtn.setDisable(false);
                webcamBtn.getStyleClass().remove("webcam-active");
                if (!webcamBtn.getStyleClass().contains("accent")) webcamBtn.getStyleClass().add("accent");
                uploadBtn.setDisable(false);
                scanBtn.setDisable(false);
                clearBtn.setDisable(false);
            }
            case SCANNING -> {
                webcamBtn.setDisable(true);
                uploadBtn.setDisable(true);
                scanBtn.setDisable(true);
                clearBtn.setDisable(true);
            }
        }
    }

    // ── Image upload/scan (existing) ────────────────────────────

    @FXML
    public void onUpload() {
        // Stop webcam if active
        if (mode == MonitorMode.WEBCAM_RUNNING || mode == MonitorMode.WEBCAM_STARTING) {
            stopWebcam();
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Image to Scan");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = chooser.showOpenDialog(imageView.getScene().getWindow());
        if (file == null) {
            if (currentImage == null) updateMode(MonitorMode.IDLE);
            return;
        }

        try {
            currentImageBytes = Files.readAllBytes(file.toPath());
            currentImage = ImageIO.read(new ByteArrayInputStream(currentImageBytes));
            if (currentImage == null) {
                log.warn("Could not read image: {}", file.getName());
                return;
            }

            Image fxImage = ImageUtils.toFxImage(currentImageBytes);
            imageView.setImage(fxImage);
            emptyImageLabel.setVisible(false);
            emptyImageLabel.setManaged(false);

            updateMode(MonitorMode.IMAGE_LOADED);

            // Clear previous results
            lastResults.clear();
            clearOverlay();
            showEmptyResults();
            updateStatus(0, 0);

            // Auto-scan on upload
            onScan();
        } catch (IOException e) {
            log.error("Failed to load image: {}", file.getName(), e);
        }
    }

    @FXML
    public void onScan() {
        if (currentImage == null) return;

        // Cancel any running scan
        if (activeScanThread != null && activeScanThread.isAlive()) {
            activeScanThread.interrupt();
        }

        setScanning(true);
        ensureServicesInitialized();

        // Capture refs for thread safety
        final BufferedImage imageToScan = currentImage;

        Thread thread = new Thread(() -> {
            try {
                matchingService.refreshCache();
                if (Thread.interrupted()) return;

                List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(imageToScan);
                if (Thread.interrupted()) return;

                List<FaceResult> results = new ArrayList<>();
                int matchCount = 0;

                for (DetectedFace face : faces) {
                    if (Thread.interrupted()) return;

                    Embedding embedding = embeddingService.extractEmbedding(face);
                    if (Thread.interrupted()) return;

                    List<MatchResult> matches = matchingService.findMatches(embedding);

                    if (!matches.isEmpty()) {
                        MatchResult best = matches.get(0);
                        results.add(new FaceResult(face, best));
                        matchCount++;

                        DetectionLog dl = new DetectionLog();
                        dl.setCriminalId(best.getCriminalId());
                        dl.setConfidence(best.getConfidence());
                        dl.setScreenshot(ImageUtils.toBytes(face.getCroppedFace(), "png"));
                        dl.setNotes("Image scan match: " + best.getCriminalName());
                        detectionLogRepo.insert(dl);
                    } else {
                        results.add(new FaceResult(face, null));
                    }
                }

                final List<FaceResult> finalResults = results;
                final int faceCount = faces.size();
                final int finalMatchCount = matchCount;

                Platform.runLater(() -> {
                    lastResults = finalResults;
                    drawOverlay();
                    buildResultCards(finalResults);
                    updateStatus(faceCount, finalMatchCount);
                });
            } catch (Throwable t) {
                if (!(t instanceof InterruptedException)) {
                    log.error("Scan failed", t);
                    Platform.runLater(() -> progressLabel.setText("Scan failed: " + t.getMessage()));
                }
            } finally {
                Platform.runLater(() -> setScanning(false));
            }
        });
        thread.setDaemon(true);
        activeScanThread = thread;
        thread.start();
    }

    @FXML
    public void onClear() {
        // Stop webcam if active
        if (mode == MonitorMode.WEBCAM_RUNNING || mode == MonitorMode.WEBCAM_STARTING) {
            stopWebcam();
        }

        // Cancel any running scan
        if (activeScanThread != null && activeScanThread.isAlive()) {
            activeScanThread.interrupt();
        }

        currentImage = null;
        currentImageBytes = null;
        imageView.setImage(null);
        lastResults.clear();
        clearOverlay();
        showEmptyState();
        updateMode(MonitorMode.IDLE);
        updateStatus(0, 0);
    }

    // ── Overlay drawing ─────────────────────────────────────────

    private void drawOverlay() {
        if (imageView.getImage() == null || lastResults.isEmpty()) {
            clearOverlay();
            return;
        }

        Image img = imageView.getImage();
        double imgW = img.getWidth();
        double imgH = img.getHeight();

        // Compute displayed size (preserveRatio)
        double viewW = imageView.getFitWidth();
        double viewH = imageView.getFitHeight();
        if (viewW <= 0) viewW = imageContainer.getWidth() - 48;
        if (viewH <= 0) viewH = imageContainer.getHeight() - 48;

        double scaleX = viewW / imgW;
        double scaleY = viewH / imgH;
        double scale = Math.min(scaleX, scaleY);

        double displayW = imgW * scale;
        double displayH = imgH * scale;

        // Offset to center
        double offsetX = (imageContainer.getWidth() - displayW) / 2.0;
        double offsetY = (imageContainer.getHeight() - displayH) / 2.0;

        overlayCanvas.setWidth(imageContainer.getWidth());
        overlayCanvas.setHeight(imageContainer.getHeight());

        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
        gc.setLineWidth(2.5);
        gc.setFont(Font.font("System", FontWeight.BOLD, 13));

        for (FaceResult fr : lastResults) {
            DetectedFace face = fr.face;
            double x = offsetX + face.getX() * scale;
            double y = offsetY + face.getY() * scale;
            double w = face.getWidth() * scale;
            double h = face.getHeight() * scale;

            if (fr.match != null) {
                // Matched: red
                gc.setStroke(Color.RED);
                gc.strokeRect(x, y, w, h);

                String label = fr.match.getCriminalName() + " "
                        + String.format("%.0f%%", fr.match.getConfidence() * 100);
                gc.setFill(Color.rgb(200, 0, 0, 0.7));
                gc.fillRect(x, y - 20, gc.getFont().getSize() * label.length() * 0.6 + 8, 20);
                gc.setFill(Color.WHITE);
                gc.fillText(label, x + 4, y - 5);
            } else {
                // Unknown: green
                gc.setStroke(Color.LIMEGREEN);
                gc.strokeRect(x, y, w, h);

                String label = "Unknown";
                gc.setFill(Color.rgb(0, 140, 0, 0.7));
                gc.fillRect(x, y - 20, gc.getFont().getSize() * label.length() * 0.6 + 8, 20);
                gc.setFill(Color.WHITE);
                gc.fillText(label, x + 4, y - 5);
            }
        }
    }

    private void clearOverlay() {
        GraphicsContext gc = overlayCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
    }

    // ── Result cards ────────────────────────────────────────────

    private void buildResultCards(List<FaceResult> results) {
        resultsBox.getChildren().clear();

        // Filter to only matched faces
        List<FaceResult> matched = results.stream()
                .filter(r -> r.match != null).toList();

        if (matched.isEmpty()) {
            Label noMatch = new Label(results.isEmpty()
                    ? "No faces detected in the image."
                    : "No matches found. " + results.size() + " face(s) detected but none match enrolled criminals.");
            noMatch.setWrapText(true);
            noMatch.getStyleClass().add("text-muted");
            noMatch.setPadding(new Insets(16));
            resultsBox.getChildren().add(noMatch);
            return;
        }

        for (FaceResult fr : matched) {
            VBox card = createMatchCard(fr);
            resultsBox.getChildren().add(card);
        }
    }

    private VBox createMatchCard(FaceResult fr) {
        MatchResult match = fr.match;

        VBox card = new VBox(8);
        card.getStyleClass().add("match-card");

        // Top row: thumbnail + name/status
        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // Criminal thumbnail
        ImageView thumb = new ImageView();
        thumb.setFitWidth(48);
        thumb.setFitHeight(48);
        thumb.setPreserveRatio(true);
        thumb.setSmooth(true);

        try {
            List<CriminalPhoto> photos = criminalService.getPhotos(match.getCriminalId());
            if (!photos.isEmpty()) {
                thumb.setImage(ImageUtils.toFxImage(photos.get(0).getPhotoData(), 48, 48));
            }
        } catch (Exception e) {
            log.warn("Failed to load photo for criminal {}", match.getCriminalId());
        }

        // Name + status
        VBox nameBox = new VBox(2);
        Label nameLabel = new Label(match.getCriminalName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label statusBadge = new Label(match.getStatus().name());
        String statusStyle = switch (match.getStatus()) {
            case WANTED -> "status-wanted";
            case ARRESTED -> "status-arrested";
            case RELEASED -> "status-released";
        };
        statusBadge.getStyleClass().add(statusStyle);

        nameBox.getChildren().addAll(nameLabel, statusBadge);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        topRow.getChildren().addAll(thumb, nameBox);

        // Confidence bar
        VBox confBox = new VBox(4);
        double conf = match.getConfidence();
        Label confLabel = new Label(String.format("Confidence: %.1f%%", conf * 100));
        confLabel.setStyle("-fx-font-size: 12px;");

        StackPane barBg = new StackPane();
        barBg.getStyleClass().add("confidence-bar");
        barBg.setMaxWidth(Double.MAX_VALUE);

        Region barFill = new Region();
        barFill.getStyleClass().add("confidence-fill");
        barFill.setMaxWidth(Double.MAX_VALUE);

        // Color based on confidence
        Color barColor;
        if (conf >= 0.8) {
            barColor = Color.web("#d32f2f"); // high = red/danger
        } else if (conf >= 0.65) {
            barColor = Color.web("#f57c00"); // medium = warning
        } else {
            barColor = Color.web("#388e3c"); // low = greenish
        }
        barFill.setStyle("-fx-background-color: " + toHex(barColor) + "; -fx-background-radius: 3; -fx-pref-height: 8;");

        // Bind fill width to percentage of bar
        barBg.widthProperty().addListener((obs, o, n) -> {
            barFill.setPrefWidth(n.doubleValue() * conf);
            barFill.setMaxWidth(n.doubleValue() * conf);
        });

        StackPane barContainer = new StackPane(barBg, barFill);
        StackPane.setAlignment(barFill, Pos.CENTER_LEFT);

        confBox.getChildren().addAll(confLabel, barContainer);

        card.getChildren().addAll(topRow, confBox);

        // Click to navigate to criminal detail
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> {
            if (mainController != null) {
                criminalService.findById(match.getCriminalId()).ifPresent(c ->
                        mainController.showCriminalDetail(c));
            }
        });

        return card;
    }

    // ── UI helpers ──────────────────────────────────────────────

    private void showEmptyState() {
        emptyImageLabel.setText("Drop or upload an image to scan");
        emptyImageLabel.setVisible(true);
        emptyImageLabel.setManaged(true);
        showEmptyResults();
    }

    private void showEmptyResults() {
        resultsBox.getChildren().clear();
        Label empty = new Label("No matches detected.\nUpload an image to scan.");
        empty.setWrapText(true);
        empty.getStyleClass().add("text-muted");
        empty.setPadding(new Insets(16));
        resultsBox.getChildren().add(empty);
    }

    private void updateStatus(int faces, int matches) {
        statusLabel.setText("Faces detected: " + faces + " | Matches: " + matches);
    }

    private void setScanning(boolean scanning) {
        if (scanning) {
            updateMode(MonitorMode.SCANNING);
        } else {
            updateMode(currentImage != null ? MonitorMode.IMAGE_LOADED : MonitorMode.IDLE);
        }
        progressIndicator.setVisible(scanning);
        progressIndicator.setManaged(scanning);
        progressLabel.setText(scanning ? "Scanning..." : "");
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    /** Pairs a detected face with its optional match result. */
    private record FaceResult(DetectedFace face, MatchResult match) {}
}
