package com.crimdet;

import atlantafx.base.theme.NordDark;
import com.crimdet.config.DatabaseConfig;
import com.crimdet.service.FaceEmbeddingService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage stage) throws Exception {
        Application.setUserAgentStylesheet(new NordDark().getUserAgentStylesheet());

        // Initialize database
        DatabaseConfig.getInstance();

        // Migrate stale embeddings (wrong dimension) before UI loads
        new FaceEmbeddingService().migrateEmbeddingsIfNeeded();

        Parent root = FXMLLoader.load(getClass().getResource("/fxml/main.fxml"));
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/css/application.css").toExternalForm());

        stage.setTitle("CrimDet \u2014 Criminal Face Detection System");
        stage.setScene(scene);
        stage.show();

        log.info("CrimDet started");
    }

    @Override
    public void stop() {
        DatabaseConfig.getInstance().close();
        log.info("CrimDet shutdown");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
