package com.crimdet.controller;

import com.crimdet.model.Criminal;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private StackPane contentArea;
    @FXML private ToggleButton dashboardBtn;
    @FXML private ToggleButton liveMonitorBtn;
    @FXML private ToggleButton criminalListBtn;
    @FXML private ToggleButton detectionHistoryBtn;

    private final ToggleGroup navGroup = new ToggleGroup();
    private final Map<String, Node> screenCache = new HashMap<>();
    private final Map<String, Object> controllerCache = new HashMap<>();

    @FXML
    public void initialize() {
        dashboardBtn.setToggleGroup(navGroup);
        liveMonitorBtn.setToggleGroup(navGroup);
        criminalListBtn.setToggleGroup(navGroup);
        detectionHistoryBtn.setToggleGroup(navGroup);

        // Prevent deselecting all toggles
        navGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) oldVal.setSelected(true);
        });

        // Start on dashboard
        dashboardBtn.setSelected(true);
        showDashboard();
    }

    @FXML
    public void showDashboard() {
        loadScreen("dashboard");
        Object ctrl = controllerCache.get("dashboard");
        if (ctrl instanceof DashboardController dc) {
            dc.refresh();
        }
    }

    @FXML
    public void showLiveMonitor() {
        loadScreen("live-monitor");
    }

    @FXML
    public void showCriminalList() {
        loadScreen("criminal-list");
        // Refresh list when navigating back
        Object ctrl = controllerCache.get("criminal-list");
        if (ctrl instanceof CriminalListController clc) {
            clc.refresh();
        }
    }

    @FXML
    public void showDetectionHistory() {
        loadScreen("detection-history");
        Object ctrl = controllerCache.get("detection-history");
        if (ctrl instanceof DetectionHistoryController dhc) {
            dhc.refresh();
        }
    }

    public void showCriminalDetail(Criminal criminal) {
        loadScreen("criminal-detail");
        Object ctrl = controllerCache.get("criminal-detail");
        if (ctrl instanceof CriminalDetailController cdc) {
            cdc.setCriminal(criminal);
        }
    }

    public void showCriminalForm(Criminal criminal) {
        loadScreen("criminal-form");
        Object ctrl = controllerCache.get("criminal-form");
        if (ctrl instanceof CriminalFormController cfc) {
            cfc.setCriminal(criminal);
        }
    }

    private void loadScreen(String name) {
        Node screen = screenCache.get(name);
        if (screen == null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + name + ".fxml"));
                screen = loader.load();
                Object controller = loader.getController();
                screenCache.put(name, screen);
                controllerCache.put(name, controller);

                // Wire up navigation for controllers that need it
                if (controller instanceof CriminalListController clc) {
                    clc.setMainController(this);
                }
                if (controller instanceof CriminalFormController cfc) {
                    cfc.setMainController(this);
                }
                if (controller instanceof CriminalDetailController cdc) {
                    cdc.setMainController(this);
                }
                if (controller instanceof LiveMonitorController lmc) {
                    lmc.setMainController(this);
                }
                if (controller instanceof DashboardController dc) {
                    dc.setMainController(this);
                }
                if (controller instanceof DetectionHistoryController dhc) {
                    dhc.setMainController(this);
                }
            } catch (IOException e) {
                log.error("Failed to load screen: {}", name, e);
                return;
            }
        }
        contentArea.getChildren().setAll(screen);
    }
}
