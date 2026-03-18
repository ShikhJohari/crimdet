package com.crimdet.controller;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.DetectionLog;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.DetectionLogRepository;
import com.crimdet.util.CsvExporter;
import com.crimdet.util.ImageUtils;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DetectionHistoryController {

    private static final Logger log = LoggerFactory.getLogger(DetectionHistoryController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PAGE_SIZE = 20;

    @FXML private TableView<DetectionLog> historyTable;
    @FXML private TableColumn<DetectionLog, ImageView> screenshotCol;
    @FXML private TableColumn<DetectionLog, String> criminalCol;
    @FXML private TableColumn<DetectionLog, String> confidenceCol;
    @FXML private TableColumn<DetectionLog, String> detectedAtCol;
    @FXML private TableColumn<DetectionLog, String> notesCol;

    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;

    @FXML private Button prevBtn;
    @FXML private Button nextBtn;
    @FXML private Label pageLabel;
    @FXML private Label emptyLabel;

    private MainController mainController;
    private final DetectionLogRepository detectionLogRepo;
    private final CriminalRepository criminalRepo;

    private int currentPage = 0;
    private long totalCount = 0;

    public DetectionHistoryController() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.detectionLogRepo = new DetectionLogRepository(jdbi);
        this.criminalRepo = new CriminalRepository(jdbi);
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        fromDate.setValue(LocalDate.now().minusDays(30));
        toDate.setValue(LocalDate.now());

        // Screenshot column
        screenshotCol.setCellValueFactory(cd -> {
            byte[] data = cd.getValue().getScreenshot();
            if (data != null && data.length > 0) {
                ImageView iv = new ImageView(ImageUtils.toFxImage(data, 50, 50));
                iv.setFitWidth(50);
                iv.setFitHeight(50);
                iv.setPreserveRatio(true);
                return new SimpleObjectProperty<>(iv);
            }
            return new SimpleObjectProperty<>(null);
        });

        // Criminal name column
        criminalCol.setCellValueFactory(cd -> {
            Long criminalId = cd.getValue().getCriminalId();
            if (criminalId != null) {
                Optional<Criminal> opt = criminalRepo.findById(criminalId);
                return new SimpleStringProperty(opt.map(Criminal::getName).orElse("Unknown (#" + criminalId + ")"));
            }
            return new SimpleStringProperty("Unknown");
        });

        // Confidence column
        confidenceCol.setCellValueFactory(cd -> {
            double conf = cd.getValue().getConfidence();
            return new SimpleStringProperty(String.format("%.1f%%", conf * 100));
        });

        // Detected at column
        detectedAtCol.setCellValueFactory(cd -> {
            LocalDateTime dt = cd.getValue().getDetectedAt();
            return new SimpleStringProperty(dt != null ? dt.format(DATE_FMT) : "");
        });

        // Notes column
        notesCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getNotes() != null ? cd.getValue().getNotes() : ""));
    }

    public void refresh() {
        currentPage = 0;
        loadPage();
    }

    @FXML
    public void onApplyFilter() {
        currentPage = 0;
        loadPage();
    }

    @FXML
    public void onPrevPage() {
        if (currentPage > 0) {
            currentPage--;
            loadPage();
        }
    }

    @FXML
    public void onNextPage() {
        int totalPages = getTotalPages();
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadPage();
        }
    }

    @FXML
    public void onExportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Detection History");
        chooser.setInitialFileName("detection-history.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        File file = chooser.showSaveDialog(historyTable.getScene().getWindow());
        if (file == null) return;

        try {
            LocalDateTime from = fromDate.getValue().atStartOfDay();
            LocalDateTime to = toDate.getValue().atTime(23, 59, 59);
            List<DetectionLog> allLogs = detectionLogRepo.findByDateRange(from, to);

            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"ID", "Criminal Name", "Confidence", "Detected At", "Notes"});

            for (DetectionLog dl : allLogs) {
                String name = "Unknown";
                if (dl.getCriminalId() != null) {
                    name = criminalRepo.findById(dl.getCriminalId())
                            .map(Criminal::getName)
                            .orElse("Unknown (#" + dl.getCriminalId() + ")");
                }
                rows.add(new String[]{
                        String.valueOf(dl.getId()),
                        name,
                        String.format("%.1f%%", dl.getConfidence() * 100),
                        dl.getDetectedAt() != null ? dl.getDetectedAt().format(DATE_FMT) : "",
                        dl.getNotes() != null ? dl.getNotes() : ""
                });
            }

            CsvExporter.exportDetectionLogs(rows, file);
            log.info("Exported {} detection logs to {}", allLogs.size(), file.getAbsolutePath());

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Exported " + allLogs.size() + " records to " + file.getName(),
                    ButtonType.OK);
            alert.setHeaderText("Export Complete");
            alert.showAndWait();
        } catch (Exception e) {
            log.error("CSV export failed", e);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Export failed: " + e.getMessage(), ButtonType.OK);
            alert.setHeaderText("Export Error");
            alert.showAndWait();
        }
    }

    private void loadPage() {
        try {
            LocalDateTime from = fromDate.getValue().atStartOfDay();
            LocalDateTime to = toDate.getValue().atTime(23, 59, 59);

            totalCount = detectionLogRepo.countByDateRange(from, to);
            int totalPages = getTotalPages();

            if (totalCount == 0) {
                historyTable.setItems(FXCollections.observableArrayList());
                historyTable.setVisible(false);
                historyTable.setManaged(false);
                emptyLabel.setVisible(true);
                emptyLabel.setManaged(true);

                // Check if it's truly empty or just filtered
                long allCount = detectionLogRepo.count();
                if (allCount == 0) {
                    emptyLabel.setText("No detections recorded yet. Matches appear here after scanning.");
                } else {
                    emptyLabel.setText("No detections match your filters.");
                }

                prevBtn.setDisable(true);
                nextBtn.setDisable(true);
                pageLabel.setText("Page 0 of 0");
                return;
            }

            emptyLabel.setVisible(false);
            emptyLabel.setManaged(false);
            historyTable.setVisible(true);
            historyTable.setManaged(true);

            int offset = currentPage * PAGE_SIZE;
            List<DetectionLog> logs = detectionLogRepo.findByDateRange(from, to, PAGE_SIZE, offset);
            historyTable.setItems(FXCollections.observableArrayList(logs));

            prevBtn.setDisable(currentPage == 0);
            nextBtn.setDisable(currentPage >= totalPages - 1);
            pageLabel.setText("Page " + (currentPage + 1) + " of " + totalPages);
        } catch (Exception e) {
            log.error("Failed to load detection history", e);
        }
    }

    private int getTotalPages() {
        return Math.max(1, (int) Math.ceil((double) totalCount / PAGE_SIZE));
    }
}
