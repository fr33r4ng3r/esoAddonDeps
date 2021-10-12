package incamoon;

import com.jfoenix.controls.JFXComboBox;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Controller implements Initializable {

    private static final Logger LOG = Logger.getLogger(Controller.class.getName());

    private LibsAnalyser analyser;

    private Stage stage;

    private Preferences preferences;

    private ApiVersions apiVersions;

    @FXML
    public VBox container;

    @FXML
    public ProgressIndicator progress;

    @FXML
    private TextArea log;

    @FXML
    private JFXComboBox<String> versions;

    @FXML
    private Text message;

    @FXML
    private Button browse;

    @FXML
    private Button analyse;

    @FXML
    private TextField folder;

    @FXML
    private TableView<LibsAnalyser.Addon> libsTable;

    @FXML
    private TableColumn<LibsAnalyser.Addon, String> libsNameColumn;

    @FXML
    private TableColumn<LibsAnalyser.Addon, Integer> libsOODColumn;

    @FXML
    private TableColumn<LibsAnalyser.Addon, String> libsSTColumn;

    @FXML
    private TableColumn<LibsAnalyser.Addon, String> libsVersionColumm;

    @FXML
    private TableColumn<LibsAnalyser.Addon, List<LibsAnalyser.AddonIdent>> libsDependenciesColumm;

    @FXML
    private TableColumn<LibsAnalyser.Addon, List<LibsAnalyser.AddonIdent>> libsOptionalDependenciesColumm;

    @FXML
    private TableView<LibsAnalyser.Addon> addsTable;

    @FXML
    private TableColumn<LibsAnalyser.Addon, String> addsNameColumn;

    @FXML
    private TableColumn<LibsAnalyser.Addon, Integer> addsOODColumn;

    @FXML
    private TableColumn<LibsAnalyser.Addon, String> addsVersionColumm;

    @FXML
    private TableColumn<LibsAnalyser.Addon, List<LibsAnalyser.AddonIdent>> addsDependenciesColumm;

    @FXML
    private TableColumn<LibsAnalyser.Addon, List<LibsAnalyser.AddonIdent>> addsOptionalDependenciesColumm;

    @FXML
    private TableView<LibsAnalyser.AddonIdent> libsMissing;

    @FXML
    private TableColumn<LibsAnalyser.AddonIdent, String> missNameColumn;

    @FXML
    private TableView<LibsAnalyser.AddonIdent> libsDuplicated;

    @FXML
    private TableColumn<LibsAnalyser.AddonIdent, String> dupsNameColumn;

    private LibsAnalyser.Addon selectedLib;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        browse.setOnAction(this::onBrowse);
        analyse.setOnAction(this::onAnalyse);
        versions.setOnAction(this::onVersion);

        preferences = Preferences.userNodeForPackage(MainApp.class);
        final String folderLocation = preferences.get("addonFolderLocation", null);
        if (folderLocation != null) {
            folder.setText(folderLocation);
            analyse.setDisable(false);
        }

        libsTable.widthProperty().addListener((observable, oldValue, newValue) -> {
            libsOptionalDependenciesColumm.setPrefWidth(newValue.doubleValue() - 550.0d);
        });

        addsTable.widthProperty().addListener((observable, oldValue, newValue) -> {
            double delta = newValue.doubleValue() - 850.0d;
            if (delta > 400) {
                addsDependenciesColumm.setPrefWidth(delta);
            }
        });

        libsNameColumn.setCellValueFactory(new PropertyValueFactory<>("title"));
        libsNameColumn.setCellFactory(this::decorateLibName);
        libsOODColumn.setCellFactory(this::decorateOOD);
        libsOODColumn.setCellValueFactory(new PropertyValueFactory<>("apiVersion"));
        libsVersionColumm.setCellValueFactory(new PropertyValueFactory<>("version"));
        libsDependenciesColumm.setCellValueFactory(param -> new ReadOnlyObjectWrapper<>(param.getValue().dependsOn));
        libsDependenciesColumm.setCellFactory(param -> new DependenciesTableCell());
        libsOptionalDependenciesColumm.setCellValueFactory(param -> new ReadOnlyObjectWrapper<>(param.getValue().getOptionalDependsOn()));
        libsOptionalDependenciesColumm.setCellFactory(param -> new DependenciesTableCell());

        addsNameColumn.setCellValueFactory(new PropertyValueFactory<>("title"));
        addsNameColumn.setCellFactory(this::decorateName);
        addsOODColumn.setCellFactory(this::decorateOOD);
        addsOODColumn.setCellValueFactory(new PropertyValueFactory<>("apiVersion"));
        addsVersionColumm.setCellValueFactory(new PropertyValueFactory<>("version"));
        addsDependenciesColumm.setCellValueFactory(param -> new ReadOnlyObjectWrapper<>(param.getValue().getDependsOn()));
        addsDependenciesColumm.setCellFactory(param -> new DependenciesTableCell());
        addsOptionalDependenciesColumm.setCellValueFactory(param -> new ReadOnlyObjectWrapper<>(param.getValue().getOptionalDependsOn()));
        addsOptionalDependenciesColumm.setCellFactory(param -> new DependenciesTableCell());

        missNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        dupsNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));

        apiVersions = new ApiVersions();

        libsTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            this.selectedLib = newValue;
            addsTable.refresh();
        });

    }

    private TableCell<LibsAnalyser.Addon, Integer> decorateOOD(TableColumn<LibsAnalyser.Addon, Integer> column) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                if (!empty) {
                    if (versions.getValue() != null && versions.getValue().length() > 0) {
                        final int version = Integer.parseInt(versions.getValue().substring(0, 6));
                        int delta = item - version;
                        setText(String.format("%,d", delta));
                        if (delta >= 0) {
                            setStyle("-fx-background-color: rgba(0,128,0,0.15); -fx-alignment: center-right; -fx-padding: 0 10 0 0;");
                        } else {
                            setStyle("-fx-background-color: rgba(128,0,0,0.15); -fx-alignment: center-right; -fx-padding: 0 10 0 0;");
                        }
                        setTooltip(new Tooltip(apiVersions.getVersionFeature(item)));
                    } else {
                        setText("");
                        setStyle("-fx-background-color: transparent; -fx-alignment: center-right; -fx-padding: 0 10 0 0;");
                        setTooltip(null);
                    }
                }
            }
        };
    }

    private TableCell<LibsAnalyser.Addon, String> decorateLibName(TableColumn<LibsAnalyser.Addon, String> column) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                final TableRow<LibsAnalyser.Addon> row = getTableRow();
                if (row == null) return;
                final LibsAnalyser.Addon addon = row.getItem();
                if (addon == null) return;
                final Text title = new Text(addon.getTitle() + "\n");
                final String paths = addon.getPaths().stream().map(p -> "[\\" + p + "]").collect(Collectors.joining(", "));
                final String pathsForTT = String.join("\n", addon.getPaths());
                final Text folder = new Text(paths);
                folder.setWrappingWidth(Double.MAX_VALUE);
                if (!empty) {
                    decorate(title, folder, row.isSelected());
                }
                final TextFlow flow = new TextFlow(title, folder);
                flow.setLineSpacing(1.1);
                setGraphic(flow);
                setTooltip(new Tooltip(pathsForTT));
                row.selectedProperty().addListener((observable, oldValue, newValue) -> {
                    decorate(title, folder, newValue);
                });
            }

            private void decorate(Text title, Text folder, boolean selected) {
                final TableRow<LibsAnalyser.Addon> row = getTableRow();
                final LibsAnalyser.Addon addon = row.getItem();
                if (selected) {
                    if (analyser.isLibraryUnreferenced(addon) && analyser.isDuplicate(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);");
                        title.setStyle("-fx-fill: #643200;");
                    } else if (analyser.isLibraryUnreferenced(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);");
                        title.setStyle("-fx-fill: black;");
                    } else if (analyser.isDuplicate(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: transparent;");
                        title.setStyle("-fx-fill: #643200;");
                    } else {
                        setStyle("-fx-alignment: top-left; -fx-background-color: transparent;");
                        title.setStyle("-fx-fill: black;");
                    }
                    if (addon.isEmbedded()) {
                        folder.setStyle("-fx-font-size: 66%; -fx-fill: #643200");
                    } else {
                        folder.setStyle("-fx-font-size: 66%; -fx-fill: #2A2E37");
                    }
                } else {
                    if (analyser.isLibraryUnreferenced(addon) && analyser.isDuplicate(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);");
                        title.setStyle("-fx-fill: #fa7d00;");
                    } else if (analyser.isLibraryUnreferenced(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);");
                        title.setStyle("-fx-fill: white;");
                    } else if (analyser.isDuplicate(addon)) {
                        setStyle("-fx-alignment: top-left; -fx-background-color: transparent;");
                        title.setStyle("-fx-fill: #fa7d00;");
                    } else {
                        setStyle("-fx-alignment: top-left; -fx-background-color: transparent;");
                        title.setStyle("-fx-fill: white;");
                    }
                    if (addon.isEmbedded()) {
                        folder.setStyle("-fx-font-size: 66%; -fx-fill: #bd8b69");
                    } else {
                        folder.setStyle("-fx-font-size: 66%; -fx-fill: #B2B2B2");
                    }
                }
                final List<MenuItem> menuItems = buildContextMenu(addon);
                if (menuItems.size() > 1) {
                    final ContextMenu menu = new ContextMenu(menuItems.toArray(MenuItem[]::new));
                    setContextMenu(menu);
                } else {
                    setContextMenu(null);
                }
            }
        };
    }

    private List<MenuItem> buildContextMenu(LibsAnalyser.Addon addon) {
        final List<MenuItem> menuItems = new LinkedList<>();
        if (analyser.isDuplicate(addon)) {
            final MenuItem titleItem = new MenuItem("Actions");
            titleItem.setDisable(true);
            titleItem.getStyleClass().add("context-menu-title");
            menuItems.add(titleItem);
            if (addon.getPaths().size() > 1 && !addon.isEmbedded()) {
                final MenuItem menuItem = new MenuItem("compress");
                menuItem.setOnAction(event -> {
                    final Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("WARNING! This cannot be undone! Are you sure?");
                    alert.setHeaderText("You are about to remove all of the duplicate library folders nested in other addons for " + addon.getTitle());
                    alert.setContentText("Please make sure you have a backup of all your addons first!");
                    alert.showAndWait().ifPresent(result -> {
                        if (result.getButtonData().isDefaultButton()) {
                            analyser.compress(addon, s -> log.appendText(s + "\n"));
                            analyse.fire();
                        }
                    });
                });
                menuItems.add(menuItem);
            } else if (addon.isEmbedded()) {
                final MenuItem menuItem = new MenuItem("delete");
                menuItem.setOnAction(event -> {
                    final String paths = addon.getPaths().stream().map(p -> "[\\" + p + "]").collect(Collectors.joining(", "));
                    final Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("WARNING!  This cannot be undone! Are you sure?");
                    alert.setHeaderText("You are about to remove nested library folder(s): " + paths);
                    alert.setContentText("Please make sure you have a backup of all your addons first!");
                    alert.showAndWait().ifPresent(result -> {
                        if (result.getButtonData().isDefaultButton()) {
                            analyser.delete(addon, s -> log.appendText(s + "\n"));
                            analyse.fire();
                        }
                    });
                });
                menuItems.add(menuItem);
            }
        }
        return menuItems;
    }

    private TableCell<LibsAnalyser.Addon, String> decorateName(TableColumn<LibsAnalyser.Addon, String> column) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                if (getTableRow() == null) return;
                final LibsAnalyser.Addon addon = getTableRow().getItem();
                if (addon == null) return;
                final Text title = new Text(addon.getTitle() + "\n");
                final String paths = addon.getPaths().stream().map(p -> "[/" + p + "]").collect(Collectors.joining(", "));
                final String pathsForTT = String.join("\n", addon.getPaths());
                final Text folder = new Text(paths);
                if (!empty) {
                    decorate(title, folder, getTableRow().isSelected());
                }
                final TextFlow flow = new TextFlow(title, folder);
                flow.setLineSpacing(1.1);
                setStyle("-fx-alignment: top-left");
                setGraphic(flow);
                setTooltip(new Tooltip(pathsForTT));
                getTableRow().selectedProperty().addListener((observable, oldValue, newValue) -> {
                    decorate(title, folder, newValue);
                });
            }

            private void decorate(Text title, Text folder, Boolean newValue) {
                final LibsAnalyser.Addon addon = getTableRow().getItem();
                if (newValue) {
                    if (addon.references(selectedLib)) {
                        title.setStyle("-fx-fill: rgb(44,43,0); -fx-font-weight: bold");
                    } else if (analyser.isDuplicate(addon)) {
                        title.setStyle("-fx-fill: #643200; -fx-background-color: transparent;");
                    } else {
                        title.setStyle("-fx-fill: black;");
                    }
                    folder.setStyle("-fx-font-size: 66%; -fx-fill: #2A2E37");
                } else {
                    if (addon.references(selectedLib)) {
                        title.setStyle("-fx-fill: rgb(236,232,0);-fx-font-weight: bold");
                    } else if (analyser.isDuplicate(addon)) {
                        title.setStyle("-fx-fill: #fa7d00; -fx-background-color: transparent;");
                    } else {
                        title.setStyle("-fx-fill: rgb(255,255,255);");
                    }
                    folder.setStyle("-fx-font-size: 66%; -fx-fill: #B2B2B2");
                }
                final List<MenuItem> menuItems = buildContextMenu(addon);
                if (menuItems.size() > 1) {
                    final ContextMenu menu = new ContextMenu(menuItems.toArray(MenuItem[]::new));
                    setContextMenu(menu);
                } else {
                    setContextMenu(null);
                }
            }
        };
    }

    private void onAnalyse(ActionEvent event) {

        container.setDisable(true);
        progress.setVisible(true);
        analyser = new LibsAnalyser(folder.getText());

        final Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    analyser.load(s -> log.appendText(s + "\n"));
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, e, () -> "Failed to Analyse Addons : " + e.getMessage());
                    message.setText("Operation Failed, please check you have the correct folder selected");
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            final ObservableList<LibsAnalyser.Addon> libs = FXCollections.observableList(analyser.getLibs());
            final ObservableList<LibsAnalyser.Addon> adds = FXCollections.observableList(analyser.getAddons());
            final ObservableList<LibsAnalyser.AddonIdent> miss = FXCollections.observableList(analyser.getMissing());
            final ObservableList<LibsAnalyser.AddonIdent> dups = FXCollections.observableList(analyser.getDuplicates());
            libsTable.setItems(libs);
            addsTable.setItems(adds);
            libsMissing.setItems(miss);
            libsDuplicated.setItems(dups);
            final ObservableList<String> vss = FXCollections.observableList(analyser.getVersions());
            versions.setItems(vss);
            versions.setDisable(false);
            vss.addListener((ListChangeListener<? super String>) c -> {
                while (c.next()) {
                    for (int i = c.getFrom(); i < c.getTo(); i++) {
                        final String s = c.getList().get(i);
                        if (s.endsWith("[*]")) {
                            versions.setValue(s);
                        }
                    }
                }
            });
            apiVersions.bind(vss);
            progress.setVisible(false);
            container.setDisable(false);
            container.requestLayout();
        });

        Executors.defaultThreadFactory().newThread(task).start();

    }

    private void onBrowse(ActionEvent event) {
        final DirectoryChooser directoryChooser = new DirectoryChooser();
        final File selectedDirectory = directoryChooser.showDialog(stage);
        if (selectedDirectory != null) {
            final String path = selectedDirectory.getPath();
            folder.setText(path);
            preferences.put("addonFolderLocation", path);
            analyse.setDisable(false);
        }
    }

    private void onVersion(ActionEvent actionEvent) {
        libsTable.refresh();
        addsTable.refresh();
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    private class DependenciesTableCell extends TableCell<LibsAnalyser.Addon, List<LibsAnalyser.AddonIdent>> {
        @Override
        protected void updateItem(List<LibsAnalyser.AddonIdent> dependsOn, boolean empty) {
            if (getTableRow() == null) return;
            if (!empty) {
                final List<Text> deps = Optional.ofNullable(dependsOn).map(d -> d.stream().flatMap(dep -> {
                    final Text text = new Text(dep.getName());
                    text.setUserData(dep);
                    text.setFont(getTableRow().getFont());
                    decorate(getTableRow().isSelected(), text, dep);
                    final Text space = new Text(", ");
                    space.setFont(getTableRow().getFont());
                    if (getTableRow().isSelected()) {
                        space.setFill(Color.BLACK);
                    } else {
                        space.setFill(Color.WHITE);
                    }
                    return Stream.of(text, space);
                }).collect(Collectors.toList())).orElseGet(Collections::emptyList);

                getTableRow().selectedProperty().addListener((observable, oldValue, newValue) -> {
                    deps.forEach(dep -> {
                        if (dep.getUserData() == null) return;
                        final LibsAnalyser.AddonIdent lib = (LibsAnalyser.AddonIdent) dep.getUserData();
                        decorate(newValue, dep, lib);
                    });
                });

                final TextFlow flow = new TextFlow(deps.stream().limit(deps.size() > 0 ? deps.size() - 1 : 0).toArray(Text[]::new));
                flow.setLineSpacing(1.1);
                setGraphic(flow);
            }
        }

        private void decorate(Boolean selected, Text text, LibsAnalyser.AddonIdent lib) {
            if (selected) {
                if (analyser.isAddonMissing(lib)) {
                    text.setStyle("-fx-fill:#690300;-fx-font-weight: bold");
                } else if (selectedLib != null && lib.getName().equals(selectedLib.getName())) {
                    text.setStyle("-fx-fill:rgb(44,43,0);-fx-font-weight: bold");
                } else {
                    text.setStyle("-fx-fill: black");
                }
            } else {
                if (analyser.isAddonMissing(lib)) {
                    text.setStyle("-fx-fill:#ed5853;-fx-font-weight: bold");
                } else if (selectedLib != null && lib.getName().equals(selectedLib.getName())) {
                    text.setStyle("-fx-fill:rgb(236,232,0);-fx-font-weight: bold");
                } else {
                    text.setStyle("-fx-fill: white");
                }
            }
        }
    }
}