package com.crimdet;

import atlantafx.base.theme.NordDark;
import com.crimdet.config.DatabaseConfig;
import com.crimdet.service.EmbeddingStore;
import com.crimdet.service.FaceRecognitionPipeline;
import com.crimdet.service.WebcamService;
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

        // Migrate stale embeddings (model_id mismatch) before UI loads. Pipeline
        // construction lazy-initializes EmbeddingStore; we then force an explicit
        // reload so the singleton reflects the post-migration DB state.
        FaceRecognitionPipeline.getInstance().migrateEmbeddingsIfNeeded();
        EmbeddingStore.getInstance().reloadFromDatabase();

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
        WebcamService.shutdownAll();
        DatabaseConfig.getInstance().close();
        log.info("CrimDet shutdown");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
