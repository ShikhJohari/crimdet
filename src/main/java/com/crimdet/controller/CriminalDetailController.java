package com.crimdet.controller;

import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.CriminalStatus;
import com.crimdet.model.DetectionLog;
import com.crimdet.repository.DetectionLogRepository;
import com.crimdet.config.DatabaseConfig;
import com.crimdet.service.CriminalService;
import com.crimdet.util.ImageUtils;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class CriminalDetailController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label nameLabel;
    @FXML private Label statusBadge;
    @FXML private Label crimeTypeLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label createdLabel;
    @FXML private Label updatedLabel;
    @FXML private FlowPane photoGrid;
    @FXML private Label noPhotosLabel;
    @FXML private TableView<DetectionLog> detectionTable;
    @FXML private TableColumn<DetectionLog, String> confidenceCol;
    @FXML private TableColumn<DetectionLog, String> detectedAtCol;
    @FXML private TableColumn<DetectionLog, String> notesCol;
    @FXML private Label noDetectionsLabel;

    private MainController mainController;
    private final CriminalService service = new CriminalService();
    private final DetectionLogRepository detectionLogRepo =
            new DetectionLogRepository(DatabaseConfig.getInstance().getJdbi());
    private Criminal criminal;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        confidenceCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.1f%%", cd.getValue().getConfidence() * 100)));
        detectedAtCol.setCellValueFactory(cd -> {
            var dt = cd.getValue().getDetectedAt();
            return new SimpleStringProperty(dt != null ? dt.format(DATE_FMT) : "");
        });
        notesCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getNotes() != null ? cd.getValue().getNotes() : ""));
    }

    public void setCriminal(Criminal criminal) {
        this.criminal = criminal;
        if (criminal == null) return;

        // Basic info
        nameLabel.setText(criminal.getName());
        crimeTypeLabel.setText(criminal.getCrimeType());
        descriptionLabel.setText(criminal.getDescription() != null ? criminal.getDescription() : "—");

        // Status badge with color
        statusBadge.setText(criminal.getStatus().name());
        statusBadge.getStyleClass().removeAll("status-wanted", "status-arrested", "status-released");
        String styleClass = switch (criminal.getStatus()) {
            case WANTED -> "status-wanted";
            case ARRESTED -> "status-arrested";
            case RELEASED -> "status-released";
        };
        statusBadge.getStyleClass().add(styleClass);

        // Timestamps
        createdLabel.setText(criminal.getCreatedAt() != null ? criminal.getCreatedAt().format(DATE_FMT) : "—");
        updatedLabel.setText(criminal.getUpdatedAt() != null ? criminal.getUpdatedAt().format(DATE_FMT) : "—");

        // Photos
        loadPhotos(criminal.getId());

        // Detection history for this criminal
        loadDetections(criminal.getId());
    }

    @FXML
    public void onBack() {
        if (mainController != null) {
            mainController.showCriminalList();
        }
    }

    @FXML
    public void onEdit() {
        if (mainController != null && criminal != null) {
            mainController.showCriminalForm(criminal);
        }
    }

    private void loadPhotos(long criminalId) {
        photoGrid.getChildren().clear();
        List<CriminalPhoto> photos = service.getPhotos(criminalId);

        if (photos.isEmpty()) {
            noPhotosLabel.setVisible(true);
            noPhotosLabel.setManaged(true);
        } else {
            noPhotosLabel.setVisible(false);
            noPhotosLabel.setManaged(false);
            for (CriminalPhoto photo : photos) {
                ImageView iv = new ImageView(ImageUtils.toFxImage(photo.getPhotoData(), 140, 140));
                iv.setFitWidth(140);
                iv.setFitHeight(140);
                iv.setPreserveRatio(true);

                StackPane tile = new StackPane(iv);
                tile.getStyleClass().add("photo-tile");
                tile.setPrefSize(150, 150);
                photoGrid.getChildren().add(tile);
            }
        }
    }

    private void loadDetections(long criminalId) {
        List<DetectionLog> logs = detectionLogRepo.findByCriminalId(criminalId);

        if (logs.isEmpty()) {
            noDetectionsLabel.setVisible(true);
            noDetectionsLabel.setManaged(true);
            detectionTable.setVisible(false);
            detectionTable.setManaged(false);
        } else {
            noDetectionsLabel.setVisible(false);
            noDetectionsLabel.setManaged(false);
            detectionTable.setVisible(true);
            detectionTable.setManaged(true);
            detectionTable.setItems(FXCollections.observableArrayList(logs));
        }
    }
}
