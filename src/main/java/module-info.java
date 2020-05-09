module eso.addon.deps.main {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.jfoenix;
    requires java.prefs;
    requires java.logging;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires static lombok;

    opens incamoon to javafx.base, javafx.fxml;
    exports incamoon to javafx.fxml, javafx.graphics;
}