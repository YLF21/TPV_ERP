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
        FXMLLoader loader = new FXMLLoader(AppVentaApplication.class.getResource("login.fxml"), messages);

        Scene scene = new Scene(loader.load(), 760, 560);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());

        stage.setTitle(messages.getString("app.title"));
        stage.setMinWidth(720);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
