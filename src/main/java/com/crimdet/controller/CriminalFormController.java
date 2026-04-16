package com.crimdet.controller;

import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.CriminalStatus;
import com.crimdet.service.CriminalService;
import com.crimdet.service.FaceRecognitionPipeline;
import com.crimdet.util.ImageUtils;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class CriminalFormController {

    private static final Logger log = LoggerFactory.getLogger(CriminalFormController.class);

    @FXML private Label formTitle;
    @FXML private TextField nameField;
    @FXML private TextField crimeTypeField;
    @FXML private TextArea descriptionField;
    @FXML private ComboBox<CriminalStatus> statusCombo;
    @FXML private FlowPane photoGrid;
    @FXML private Label noPhotosLabel;
    @FXML private Button deleteBtn;
    @FXML private Button saveBtn;

    private MainController mainController;
    private final CriminalService service = new CriminalService();
    private Criminal editingCriminal;

    // Track new photos (not yet saved) as byte arrays
    private final List<byte[]> pendingPhotos = new ArrayList<>();
    // Track existing photo IDs to delete
    private final List<Long> photosToDelete = new ArrayList<>();
    // Track face validation results per photo (true = exactly 1 face detected)
    private final Map<byte[], Boolean> faceValidation = new IdentityHashMap<>();
    // Cached existing photos for the current form (used to preserve grid on async refresh)
    private List<CriminalPhoto> currentExistingPhotos = List.of();

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        statusCombo.setItems(FXCollections.observableArrayList(CriminalStatus.values()));
        statusCombo.setValue(CriminalStatus.WANTED);
    }

    public void setCriminal(Criminal criminal) {
        this.editingCriminal = criminal;
        pendingPhotos.clear();
        photosToDelete.clear();
        faceValidation.clear();
        currentExistingPhotos = List.of();

        if (criminal != null) {
            // Edit mode
            formTitle.setText("Edit Criminal");
            nameField.setText(criminal.getName());
            crimeTypeField.setText(criminal.getCrimeType());
            descriptionField.setText(criminal.getDescription() != null ? criminal.getDescription() : "");
            statusCombo.setValue(criminal.getStatus());
            deleteBtn.setVisible(true);
            deleteBtn.setManaged(true);
            loadExistingPhotos(criminal.getId());
        } else {
            // Add mode
            formTitle.setText("Add Criminal");
            nameField.clear();
            crimeTypeField.clear();
            descriptionField.clear();
            statusCombo.setValue(CriminalStatus.WANTED);
            deleteBtn.setVisible(false);
            deleteBtn.setManaged(false);
            refreshPhotoGrid();
        }
    }

    @FXML
    public void onUploadPhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Photo");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.bmp")
        );
        List<File> files = chooser.showOpenMultipleDialog(photoGrid.getScene().getWindow());
        if (files != null) {
            for (File file : files) {
                try {
                    byte[] data = Files.readAllBytes(file.toPath());
                    byte[] resized = ImageUtils.resizeIfNeeded(data);
                    pendingPhotos.add(resized);
                    runFaceDetection(resized);
                } catch (IOException e) {
                    log.error("Failed to read photo: {}", file.getName(), e);
                    showError("Failed to load photo: " + file.getName());
                }
            }
            refreshPhotoGrid();
        }
    }

    private void runFaceDetection(byte[] photoData) {
        Thread thread = new Thread(() -> {
            try {
                int count = FaceRecognitionPipeline.getInstance().countFaces(photoData);
                boolean valid = count == 1;
                log.info("Face detection: {} face(s) found, valid={}", count, valid);
                Platform.runLater(() -> {
                    faceValidation.put(photoData, valid);
                    refreshPhotoGrid();
                });
            } catch (Throwable t) {
                log.error("Face detection failed", t);
                Platform.runLater(() -> {
                    faceValidation.put(photoData, false);
                    refreshPhotoGrid();
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    public void onSave() {
        // Validate
        String name = nameField.getText();
        String crimeType = crimeTypeField.getText();
        if (name == null || name.isBlank()) {
            showError("Name is required.");
            return;
        }
        if (crimeType == null || crimeType.isBlank()) {
            showError("Crime type is required.");
            return;
        }

        long criminalId;

        if (editingCriminal != null) {
            // Update existing
            editingCriminal.setName(name.trim());
            editingCriminal.setCrimeType(crimeType.trim());
            editingCriminal.setDescription(descriptionField.getText());
            editingCriminal.setStatus(statusCombo.getValue());
            service.updateCriminal(editingCriminal);

            // Delete removed photos
            for (long photoId : photosToDelete) {
                service.deletePhoto(photoId);
            }

            // Add new photos
            for (byte[] photoData : pendingPhotos) {
                service.addPhoto(editingCriminal.getId(), photoData);
            }

            criminalId = editingCriminal.getId();
        } else {
            // Create new
            Criminal c = new Criminal();
            c.setName(name.trim());
            c.setCrimeType(crimeType.trim());
            c.setDescription(descriptionField.getText());
            c.setStatus(statusCombo.getValue());
            criminalId = service.addCriminal(c);

            // Add photos
            for (byte[] photoData : pendingPhotos) {
                service.addPhoto(criminalId, photoData);
            }
        }

        // Show processing state and enroll embeddings on background thread
        saveBtn.setDisable(true);
        saveBtn.setText("Processing...");

        Thread enrollThread = new Thread(() -> {
            try {
                // Enrollment publishes into EmbeddingStore, so all live readers
                // (webcam, image scan) see the new criminal on their next snapshot.
                FaceRecognitionPipeline.getInstance().enrollCriminal(criminalId);
                log.info("Embedding enrollment complete for criminal id={}", criminalId);
            } catch (Throwable t) {
                log.error("Embedding enrollment failed for criminal id={}", criminalId, t);
            } finally {
                Platform.runLater(() -> {
                    saveBtn.setDisable(false);
                    saveBtn.setText("Save");
                    onBack();
                });
            }
        });
        enrollThread.setDaemon(true);
        enrollThread.start();
    }

    @FXML
    public void onBack() {
        if (mainController != null) {
            mainController.showCriminalList();
        }
    }

    @FXML
    public void onDelete() {
        if (editingCriminal == null) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + editingCriminal.getName() + "\"? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Confirm Delete");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                service.deleteCriminal(editingCriminal.getId());
                onBack();
            }
        });
    }

    private void loadExistingPhotos(long criminalId) {
        List<CriminalPhoto> photos = service.getPhotos(criminalId);
        // Run face detection on existing photos too
        for (CriminalPhoto photo : photos) {
            if (!photosToDelete.contains(photo.getId())) {
                runFaceDetection(photo.getPhotoData());
            }
        }
        refreshPhotoGrid(photos);
    }

    private void refreshPhotoGrid() {
        refreshPhotoGrid(currentExistingPhotos);
    }

    private void refreshPhotoGrid(List<CriminalPhoto> existingPhotos) {
        currentExistingPhotos = existingPhotos;
        photoGrid.getChildren().clear();

        boolean hasPhotos = false;

        // Show existing photos with delete button
        for (CriminalPhoto photo : existingPhotos) {
            if (photosToDelete.contains(photo.getId())) continue;
            hasPhotos = true;
            photoGrid.getChildren().add(createPhotoTile(photo.getPhotoData(), () -> {
                photosToDelete.add(photo.getId());
                faceValidation.remove(photo.getPhotoData());
                refreshPhotoGrid(existingPhotos);
            }));
        }

        // Show pending (new) photos — remove by reference to avoid index shift bugs
        for (byte[] photoData : pendingPhotos) {
            hasPhotos = true;
            photoGrid.getChildren().add(createPhotoTile(photoData, () -> {
                pendingPhotos.remove(photoData);
                faceValidation.remove(photoData);
                refreshPhotoGrid(existingPhotos);
            }));
        }

        noPhotosLabel.setVisible(!hasPhotos);
        noPhotosLabel.setManaged(!hasPhotos);
    }

    private StackPane createPhotoTile(byte[] data, Runnable onRemove) {
        ImageView iv = new ImageView(ImageUtils.toFxImage(data, 120, 120));
        iv.setFitWidth(120);
        iv.setFitHeight(120);
        iv.setPreserveRatio(true);

        Button removeBtn = new Button();
        removeBtn.setGraphic(new FontIcon("mdi2c-close-circle"));
        removeBtn.getStyleClass().addAll("flat", "danger");
        removeBtn.setOnAction(e -> onRemove.run());
        removeBtn.setTranslateX(45);
        removeBtn.setTranslateY(-45);

        StackPane tile = new StackPane(iv, removeBtn);
        tile.getStyleClass().add("photo-tile");
        tile.setPrefSize(130, 130);

        // Add face validation overlay
        if (faceValidation.containsKey(data)) {
            boolean valid = faceValidation.get(data);
            FontIcon statusIcon;
            if (valid) {
                statusIcon = new FontIcon("mdi2c-check-circle");
                statusIcon.setIconSize(24);
                statusIcon.getStyleClass().add("face-valid");
            } else {
                statusIcon = new FontIcon("mdi2c-close-circle-outline");
                statusIcon.setIconSize(24);
                statusIcon.getStyleClass().add("face-invalid");
            }
            StackPane.setAlignment(statusIcon, Pos.BOTTOM_RIGHT);
            statusIcon.setTranslateX(-8);
            statusIcon.setTranslateY(-8);
            tile.getChildren().add(statusIcon);
        }

        return tile;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText("Error");
        alert.showAndWait();
    }
}
