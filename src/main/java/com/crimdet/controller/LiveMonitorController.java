package com.crimdet.controller;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.*;
import com.crimdet.repository.DetectionLogRepository;
import com.crimdet.service.CriminalService;
import com.crimdet.service.FaceDetectionService;
import com.crimdet.service.FaceEmbeddingService;
import com.crimdet.service.FaceMatchingService;
import com.crimdet.util.ImageUtils;
import javafx.application.Platform;
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
import java.util.ArrayList;
import java.util.List;

public class LiveMonitorController {

    private static final Logger log = LoggerFactory.getLogger(LiveMonitorController.class);

    @FXML private StackPane imageContainer;
    @FXML private ImageView imageView;
    @FXML private Canvas overlayCanvas;
    @FXML private Label emptyImageLabel;
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

    private final CriminalService criminalService = new CriminalService();
    private final FaceEmbeddingService embeddingService = new FaceEmbeddingService();
    private final FaceMatchingService matchingService = new FaceMatchingService();
    private final DetectionLogRepository detectionLogRepo =
            new DetectionLogRepository(DatabaseConfig.getInstance().getJdbi());

    // Store scan results for overlay drawing
    private List<FaceResult> lastResults = new ArrayList<>();

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Bind imageView fitWidth/fitHeight to container size
        imageView.fitWidthProperty().bind(imageContainer.widthProperty().subtract(48));
        imageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(48));

        // Redraw overlay when container resizes
        imageContainer.widthProperty().addListener((obs, o, n) -> drawOverlay());
        imageContainer.heightProperty().addListener((obs, o, n) -> drawOverlay());

        showEmptyState();
    }

    @FXML
    public void onUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Image to Scan");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = chooser.showOpenDialog(imageView.getScene().getWindow());
        if (file == null) return;

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
            scanBtn.setDisable(false);
            clearBtn.setDisable(false);

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

        setScanning(true);

        Thread scanThread = new Thread(() -> {
            try {
                // Refresh match cache
                matchingService.refreshCache();

                // Detect faces
                List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(currentImage);
                List<FaceResult> results = new ArrayList<>();
                int matchCount = 0;

                for (DetectedFace face : faces) {
                    // Extract embedding
                    float[] embedding = embeddingService.extractEmbedding(face.getCroppedFace());

                    // Find matches
                    List<MatchResult> matches = matchingService.findMatches(embedding);

                    if (!matches.isEmpty()) {
                        MatchResult best = matches.get(0);
                        results.add(new FaceResult(face, best));
                        matchCount++;

                        // Log to DB
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
                    setScanning(false);
                });
            } catch (Exception e) {
                log.error("Scan failed", e);
                Platform.runLater(() -> {
                    progressLabel.setText("Scan failed");
                    setScanning(false);
                });
            }
        });
        scanThread.setDaemon(true);
        scanThread.start();
    }

    @FXML
    public void onClear() {
        currentImage = null;
        currentImageBytes = null;
        imageView.setImage(null);
        lastResults.clear();
        clearOverlay();
        showEmptyState();
        scanBtn.setDisable(true);
        clearBtn.setDisable(true);
        updateStatus(0, 0);
    }

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
        card.setStyle(card.getStyle() + "-fx-cursor: hand;");
        card.setOnMouseClicked(e -> {
            if (mainController != null) {
                criminalService.findById(match.getCriminalId()).ifPresent(c ->
                        mainController.showCriminalDetail(c));
            }
        });

        return card;
    }

    private void showEmptyState() {
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
        progressIndicator.setVisible(scanning);
        progressIndicator.setManaged(scanning);
        progressLabel.setText(scanning ? "Scanning..." : "");
        uploadBtn.setDisable(scanning);
        scanBtn.setDisable(scanning);
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
