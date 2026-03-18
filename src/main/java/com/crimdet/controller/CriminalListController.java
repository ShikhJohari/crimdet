package com.crimdet.controller;

import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalStatus;
import com.crimdet.service.CriminalService;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.format.DateTimeFormatter;

public class CriminalListController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private TableView<Criminal> criminalTable;
    @FXML private TableColumn<Criminal, Long> idCol;
    @FXML private TableColumn<Criminal, String> nameCol;
    @FXML private TableColumn<Criminal, String> crimeTypeCol;
    @FXML private TableColumn<Criminal, CriminalStatus> statusCol;
    @FXML private TableColumn<Criminal, String> createdCol;
    @FXML private TableColumn<Criminal, Void> actionsCol;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> statusFilter;

    private MainController mainController;
    private final CriminalService service = new CriminalService();

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Status filter
        statusFilter.setItems(FXCollections.observableArrayList("All Statuses", "WANTED", "ARRESTED", "RELEASED"));
        statusFilter.setValue("All Statuses");
        statusFilter.setOnAction(e -> refresh());

        // Search on typing
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refresh());

        // Column cell value factories
        idCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().getId()));
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getName()));
        crimeTypeCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getCrimeType()));

        statusCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue().getStatus()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(CriminalStatus status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status.name());
                    String styleClass = switch (status) {
                        case WANTED -> "status-wanted";
                        case ARRESTED -> "status-arrested";
                        case RELEASED -> "status-released";
                    };
                    getStyleClass().removeAll("status-wanted", "status-arrested", "status-released");
                    getStyleClass().add(styleClass);
                }
            }
        });

        createdCol.setCellValueFactory(cd -> {
            var dt = cd.getValue().getCreatedAt();
            return new SimpleStringProperty(dt != null ? dt.format(DATE_FMT) : "");
        });

        // Actions column with edit/delete buttons
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button();
            private final Button deleteBtn = new Button();
            private final HBox box = new HBox(6, editBtn, deleteBtn);

            {
                editBtn.setGraphic(new FontIcon("mdi2p-pencil"));
                editBtn.getStyleClass().add("flat");
                editBtn.setOnAction(e -> {
                    Criminal c = getTableView().getItems().get(getIndex());
                    if (mainController != null) mainController.showCriminalForm(c);
                });

                deleteBtn.setGraphic(new FontIcon("mdi2d-delete"));
                deleteBtn.getStyleClass().addAll("flat", "danger");
                deleteBtn.setOnAction(e -> {
                    Criminal c = getTableView().getItems().get(getIndex());
                    confirmDelete(c);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });

        // Double-click to view detail (read-only, not edit)
        criminalTable.setRowFactory(tv -> {
            TableRow<Criminal> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && mainController != null) {
                    mainController.showCriminalDetail(row.getItem());
                }
            });
            return row;
        });

        refresh();
    }

    @FXML
    public void onAdd() {
        if (mainController != null) {
            mainController.showCriminalForm(null);
        }
    }

    public void refresh() {
        String query = searchField != null ? searchField.getText() : null;
        String statusStr = statusFilter != null ? statusFilter.getValue() : null;
        CriminalStatus status = null;
        if (statusStr != null && !statusStr.equals("All Statuses")) {
            status = CriminalStatus.valueOf(statusStr);
        }
        var criminals = service.searchAndFilter(query, status);
        criminalTable.setItems(FXCollections.observableArrayList(criminals));
    }

    private void confirmDelete(Criminal criminal) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + criminal.getName() + "\"? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Confirm Delete");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                service.deleteCriminal(criminal.getId());
                refresh();
            }
        });
    }
}
