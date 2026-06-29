package com.tpverp.frontend.venta;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.ResourceBundle;

public class LoginController {

    private final LocalLoginService loginService = new LocalLoginService();

    @FXML
    private ResourceBundle resources;
    @FXML
    private TextField userNameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Label errorLabel;

    @FXML
    private void login() throws Exception {
        LocalLoginResult result = loginService.login(userNameField.getText(), passwordField.getText());
        if (!result.authenticated()) {
            errorLabel.setText(resources.getString("login.error.invalid"));
            return;
        }
        if (!result.canEnterAppVenta()) {
            errorLabel.setText(resources.getString("login.error.noAccess"));
            return;
        }
        FXMLLoader loader = new FXMLLoader(AppVentaApplication.class.getResource("app-venta.fxml"), resources);
        Stage stage = (Stage) userNameField.getScene().getWindow();
        Scene scene = new Scene(loader.load(), 1280, 760);
        loader.<AppVentaController>getController().setSession(result);
        scene.getStylesheets().add(AppVentaApplication.class.getResource("styles/app-venta.css").toExternalForm());
        stage.setMinWidth(1100);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.centerOnScreen();
    }
}
