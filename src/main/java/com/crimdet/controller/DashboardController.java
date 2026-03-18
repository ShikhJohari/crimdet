package com.crimdet.controller;

import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.DetectionLog;
import com.crimdet.service.CriminalService;
import com.crimdet.service.StatsService;
import com.crimdet.util.ImageUtils;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @FXML private Label threatCount;
    @FXML private Label dbSizeCount;
    @FXML private Label detectionCount;
    @FXML private VBox alertsContainer;
    @FXML private Label emptyAlertsLabel;
    @FXML private VBox statCardDanger;
    @FXML private VBox statCardAccent;
    @FXML private VBox statCardWarning;

    private MainController mainController;
    private final StatsService statsService = new StatsService();
    private final CriminalService criminalService = new CriminalService();

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        refresh();
    }

    public void refresh() {
        try {
            long wanted = statsService.getWantedCount();
            long total = statsService.getTotalCriminals();
            long todayDet = statsService.getTodayDetections();

            threatCount.setText(String.valueOf(wanted));
            dbSizeCount.setText(String.valueOf(total));
            detectionCount.setText(String.valueOf(todayDet));

            loadRecentAlerts();
        } catch (Exception e) {
            log.error("Failed to load dashboard stats", e);
        }
    }

    @FXML
    public void onRegisterSuspect() {
        if (mainController != null) {
            mainController.showCriminalForm(null);
        }
    }

    @FXML
    public void onScanImage() {
        if (mainController != null) {
            mainController.showLiveMonitor();
        }
    }

    private void loadRecentAlerts() {
        alertsContainer.getChildren().clear();

        List<DetectionLog> recent = statsService.getRecentDetections(10);

        if (recent.isEmpty()) {
            emptyAlertsLabel.setVisible(true);
            emptyAlertsLabel.setManaged(true);
        } else {
            emptyAlertsLabel.setVisible(false);
            emptyAlertsLabel.setManaged(false);

            for (DetectionLog detection : recent) {
                HBox entry = buildAlertEntry(detection);
                alertsContainer.getChildren().add(entry);
            }
        }
    }

    private HBox buildAlertEntry(DetectionLog detection) {
        HBox entry = new HBox(12);
        entry.getStyleClass().add("alert-entry");
        entry.setAlignment(Pos.CENTER_LEFT);

        // Thumbnail or icon
        if (detection.getCriminalId() != null) {
            List<CriminalPhoto> photos = criminalService.getPhotos(detection.getCriminalId());
            if (!photos.isEmpty()) {
                ImageView thumb = new ImageView(ImageUtils.toFxImage(photos.get(0).getPhotoData(), 40, 40));
                thumb.setFitWidth(40);
                thumb.setFitHeight(40);
                thumb.setPreserveRatio(true);
                entry.getChildren().add(thumb);
            } else {
                FontIcon icon = new FontIcon("mdi2a-account-group");
                icon.setIconSize(28);
                entry.getChildren().add(icon);
            }
        } else {
            FontIcon icon = new FontIcon("mdi2a-alert");
            icon.setIconSize(28);
            entry.getChildren().add(icon);
        }

        // Details column
        VBox details = new VBox(2);

        // Criminal name
        String name = "Unknown";
        Criminal criminal = null;
        if (detection.getCriminalId() != null) {
            Optional<Criminal> opt = criminalService.findById(detection.getCriminalId());
            if (opt.isPresent()) {
                criminal = opt.get();
                name = criminal.getName();
            }
        }
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-weight: bold;");

        // Confidence + time
        String confText = String.format("Confidence: %.1f%%", detection.getConfidence() * 100);
        String timeText = formatRelativeTime(detection.getDetectedAt());
        Label metaLabel = new Label(confText + "  |  " + timeText);
        metaLabel.getStyleClass().add("text-muted");

        details.getChildren().addAll(nameLabel, metaLabel);
        entry.getChildren().add(details);

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        entry.getChildren().add(spacer);

        // Chevron
        FontIcon chevron = new FontIcon("mdi2c-chevron-right");
        chevron.setIconSize(20);
        entry.getChildren().add(chevron);

        // Click handler — navigate to criminal detail if available
        final Criminal clickTarget = criminal;
        if (clickTarget != null) {
            entry.setOnMouseClicked(e -> {
                if (mainController != null) {
                    mainController.showCriminalDetail(clickTarget);
                }
            });
        }

        return entry;
    }

    private String formatRelativeTime(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        Duration duration = Duration.between(dateTime, LocalDateTime.now());

        long seconds = duration.getSeconds();
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        return dateTime.toLocalDate().toString();
    }
}
