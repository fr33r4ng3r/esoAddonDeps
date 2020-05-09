package incamoon;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;


public class MainApp extends Application {

    private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

    @Override
    public void start(Stage stage) throws Exception {

        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
            LOG.log(Level.SEVERE, throwable, throwable::getMessage);
        });

        final FXMLLoader loader = new FXMLLoader(getClass().getResource("/incamoon/scene.fxml"));

        final Parent root = loader.load();

        final Controller controller = loader.getController();
        controller.setStage(stage);

        final Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/incamoon/styles.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/incamoon/moderna-dark.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/incamoon/dark-theme.css").toExternalForm());

        final Properties props = new Properties();
        props.load(getClass().getResourceAsStream("/version.properties"));
        stage.setTitle("ESO Addon Analyser (version " + props.getProperty("version") + " by fr33r4ng3r)");
        stage.setScene(scene);
        stage.getIcons().add(new Image(MainApp.class.getResourceAsStream("/fr33r4ng3r.png")));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

}