package com.tpverp.frontend.venta;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.ResourceBundle;

public class AppVentaApplication extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Locale locale = Locale.of("es");
        ResourceBundle messages = ResourceBundle.getBundle("com.tpverp.frontend.venta.i18n.messages", locale);
        FXMLLoader loader = new FXMLLoader(AppVentaApplication.class.getResource("app-venta.fxml"), messages);

        Scene scene = new Scene(loader.load(), 1280, 760);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());

        stage.setTitle("APP VENTA");
        stage.setMinWidth(1100);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
