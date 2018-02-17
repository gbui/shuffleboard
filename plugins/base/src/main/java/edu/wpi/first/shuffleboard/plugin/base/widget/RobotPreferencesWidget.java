package edu.wpi.first.shuffleboard.plugin.base.widget;

import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.shuffleboard.api.components.ExtendedPropertySheet;
import edu.wpi.first.shuffleboard.api.data.ComplexDataType;
import edu.wpi.first.shuffleboard.api.sources.DataSource;
import edu.wpi.first.shuffleboard.api.util.AlphanumComparator;
import edu.wpi.first.shuffleboard.api.util.NetworkTableUtils;
import edu.wpi.first.shuffleboard.api.widget.Description;
import edu.wpi.first.shuffleboard.api.widget.ParametrizedController;
import edu.wpi.first.shuffleboard.api.widget.SimpleAnnotatedWidget;
import edu.wpi.first.shuffleboard.plugin.base.data.RobotPreferencesData;

import org.controlsfx.control.PropertySheet;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;

/**
 * A widget for displaying and editing preferences for an FRC robot.
 */
@Description(name = "Robot Preferences", dataTypes = RobotPreferencesData.class)
@ParametrizedController("RobotPreferencesWidget.fxml")
public class RobotPreferencesWidget extends SimpleAnnotatedWidget<RobotPreferencesData> {

  @FXML
  private Pane root;
  @FXML
  private PropertySheet propertySheet;
  @FXML
  private Button addButton;
  @FXML
  private Button removeButton;

  // Keep map of properties
  // If we just used the data map in the property sheet, a new item (and editor) would be created for each change
  // For example, typing a new character in a text field would remove that field and replace it with a new one
  // This approach makes it so that editors don't appear to lose focus when users are interacting with them
  private final ObservableMap<String, ObjectProperty<Object>> wrapperProperties = FXCollections.observableHashMap();

  private static final Comparator<PropertySheet.Item> itemSorter =
      Comparator.comparing(i -> i.getName().toLowerCase(Locale.US), AlphanumComparator.INSTANCE);

  @FXML
  private void initialize() {
    dataOrDefault.addListener((__, prevData, curData) -> {
      Map<String, Object> updated = curData.changesFrom(prevData);
      if (prevData != null) {
        // Remove items for any deleted robot preferences
        prevData.asMap().entrySet().stream()
            .map(Map.Entry::getKey)
            .filter(k -> !curData.containsKey(k))
            .forEach(wrapperProperties::remove);
      }
      updated.forEach((key, value) -> {
        if (NetworkTableUtils.isMetadata(key)) {
          return;
        }
        wrapperProperties.computeIfAbsent(key, k -> generateWrapper(k, value)).setValue(value);
      });
    });

    wrapperProperties.addListener((MapChangeListener<String, ObjectProperty<? super Object>>) change -> {
      if (change.wasAdded()) {
        propertySheet.getItems().add(new ExtendedPropertySheet.PropertyItem<>(change.getValueAdded(), change.getKey()));
      } else if (change.wasRemoved()) {
        propertySheet.getItems().removeIf(i -> i.getName().equals(change.getKey()));
      }
      propertySheet.getItems().sort(itemSorter);
    });

    addButton.setOnMouseClicked(event -> {
      NewPreferenceEntryDialog dialog = new NewPreferenceEntryDialog();
      Optional<NewPreferenceEntry> result = dialog.showAndWait();
      result.ifPresent(entry -> {
        if (!dataOrDefault.get().containsKey(entry.key)) {
          putString(entry.key, entry.value, entry.type);
        } else {
          showAlert("An entry with the key " + entry.key + " already exists", "Duplicate Key", AlertType.ERROR);
        }
      });
    });

    removeButton.setOnMouseClicked(event -> {
      RemovePreferenceEntryDialog dialog = new RemovePreferenceEntryDialog();
      Optional<String> result = dialog.showAndWait();
      result.ifPresent(key -> delete(key));
    });

    exportProperties(propertySheet.searchBoxVisibleProperty());
  }

  @Override
  public Pane getView() {
    return root;
  }

  private <T> SimpleObjectProperty<T> generateWrapper(String key, T initialValue) {
    SimpleObjectProperty<T> wrapper = new SimpleObjectProperty<>(this, key, initialValue);
    wrapper.addListener((__, prev, value) -> setData(dataOrDefault.get().put(key, value)));
    return wrapper;
  }

  private void showAlert(String message, String title, AlertType type) {
    Alert alert = new Alert(type);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.getDialogPane().setContent(new BorderPane(new Label(message)));
    alert.getDialogPane().getStylesheets().setAll(root.getScene().getRoot().getStylesheets());
    alert.showAndWait();
  }

  private boolean putString(String key, String value, String type) {
    if (!validateKey(key)) {
      return false;
    }
    Object valueObj = validateValue(value, type);
    if (valueObj == null) {
      return false;
    }
    setData(dataOrDefault.get().put(key, valueObj));
    return true;
  }

  private void delete(String key) {
    DataSource<?> source = sourceProperty().getValue();
    if (source.getType().getName().equals("NetworkTables") && source.getDataType() instanceof ComplexDataType) {
      String path = NetworkTable.normalizeKey(source.getName(), false);
      NetworkTable table = NetworkTableInstance.getDefault().getTable(path);
      table.delete(key);
    }
  }

  private boolean validateKey(String key) {
    if (key.isEmpty()) {
      showAlert("The key cannot be empty", "Bad Key", AlertType.ERROR);
      return false;
    }
    return true;
  }

  private Object validateValue(String value, String type) {
    switch (type) {
      // TODO: add other cases
      case "Boolean":
      {
        String lower = value.toLowerCase(Locale.ENGLISH);
        if (lower.equals("y") || lower.equals("yes") || lower.equals("t") || lower.equals("true")
            || lower.equals("on") || lower.equals("1")) {
          return new Boolean(true);
        } else if (lower.equals("n") || lower.equals("no") || lower.equals("f")
            || lower.equals("false") || lower.equals("off") || lower.equals("0")) {
          return new Boolean(false);
        } else {
          showAlert("Invalid boolean value; expected one of yes, true, 1, no, false, 0", "Bad Value", AlertType.ERROR);
        }
        break;
      }
      case "Number":
        try {
          return Double.parseDouble(value);
        } catch (NumberFormatException e) {
          showAlert("Invalid number value", "Bad Value", AlertType.ERROR);
        }
        break;
      case "String":
        try {
          return unescapeString(value);
        } catch (NumberFormatException e) {
          showAlert("Invalid string: " + e.getMessage(), "Bad Value", AlertType.ERROR);
        }
    }
    return null;
  }

  private static String unescapeString(String str) {
    StringBuilder out = new StringBuilder();
    unescapeString(out, str);
    return out.toString();
  }

  private static void unescapeString(StringBuilder out, String str) {
    int sz = str.length();
    StringBuilder unicode = new StringBuilder(4);
    boolean hadSlash = false;
    boolean inUnicode = false;
    for (int i = 0; i < sz; i++) {
      char ch = str.charAt(i);
      if (inUnicode) {
        // if in unicode, then we're reading unicode
        // values in somehow
        unicode.append(ch);
        if (unicode.length() == 4) {
          // unicode now contains the four hex digits
          // which represents our unicode character
          try {
            int value = Integer.parseInt(unicode.toString(), 16);
            out.append((char) value);
            unicode.setLength(0);
            inUnicode = false;
            hadSlash = false;
          } catch (NumberFormatException nfe) {
            throw new NumberFormatException("Unable to parse unicode value: " + unicode);
          }
        }
        continue;
      }
      if (hadSlash) {
        // handle an escaped value
        hadSlash = false;
        switch (ch) {
          case 'r':
            out.append('\r');
            break;
          case 'f':
            out.append('\f');
            break;
          case 't':
            out.append('\t');
            break;
          case 'n':
            out.append('\n');
            break;
          case 'b':
            out.append('\b');
            break;
          case 'u': {
            // uh-oh, we're in unicode country....
            inUnicode = true;
            break;
          }
          default:
            out.append(ch);
            break;
        }
        continue;
      } else if (ch == '\\') {
        hadSlash = true;
        continue;
      }
      out.append(ch);
    }
    if (hadSlash) {
      // then we're in the weird case of a \ at the end of the
      // string, let's output it anyway.
      out.append('\\');
    }
  }

  private class NewPreferenceEntry {
    private final String key;
    private final String type;
    private final String value;

    public NewPreferenceEntry(String key, String type, String value) {
      this.key = key;
      this.type = type;
      this.value = value;
    }
  }

  private class NewPreferenceEntryDialog extends Dialog<NewPreferenceEntry> {
    public NewPreferenceEntryDialog() {
      setTitle("New Preference Entry");
      getDialogPane().getStylesheets().setAll(root.getScene().getRoot().getStylesheets());

      ButtonType addButtonType = new ButtonType("Add", ButtonData.OK_DONE);
      getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

      GridPane grid = new GridPane();
      grid.setHgap(10);
      grid.setVgap(10);
      grid.setPadding(new Insets(20, 150, 10, 10));

      TextField key = new TextField();
      ComboBox<String> type = new ComboBox<>();
      type.getItems().addAll("Boolean", "Number", "String"); // TODO: add other types
      type.getSelectionModel().selectFirst();
      TextField value = new TextField();

      Node addButton = getDialogPane().lookupButton(addButtonType);
      addButton.disableProperty().bind(
          Bindings.or(
              key.textProperty().isEmpty(),
              Bindings.createBooleanBinding(() -> {
                return dataOrDefault.get().containsKey(key.textProperty().get());
              }, dataOrDefault, key.textProperty())));

      grid.add(new Label("Key:"), 0, 0);
      grid.add(key, 1, 0);
      grid.add(new Label("Type:"), 0, 1);
      grid.add(type, 1, 1);
      grid.add(new Label("Value:"), 0, 2);
      grid.add(value, 1, 2);

      getDialogPane().setContent(new BorderPane(grid));

      setResultConverter(dialogButton -> {
        if (dialogButton == addButtonType) {
          return new NewPreferenceEntry(key.getText(), type.getValue(), value.getText());
        }
        return null;
      });
    }
  }

  private class RemovePreferenceEntryDialog extends Dialog<String> {
    public RemovePreferenceEntryDialog() {
      setTitle("Remove Preference Entry");
      getDialogPane().getStylesheets().setAll(root.getScene().getRoot().getStylesheets());

      ButtonType removeButtonType = new ButtonType("Remove", ButtonData.OK_DONE);
      getDialogPane().getButtonTypes().addAll(removeButtonType, ButtonType.CANCEL);

      Set<String> keys = dataOrDefault.get().asMap().keySet().stream()
          .filter(key -> !NetworkTableUtils.isMetadata(key))
          .collect(Collectors.toSet());

      ComboBox<String> key = new ComboBox<>();
      key.getItems().addAll(keys);
      key.getSelectionModel().selectFirst();

      Node removeButton = getDialogPane().lookupButton(removeButtonType);
      removeButton.disableProperty().bind(key.getSelectionModel().selectedItemProperty().isNull());

      getDialogPane().setContent(new BorderPane(key));

      setResultConverter(dialogButton -> {
        if (dialogButton == removeButtonType) {
          return key.getValue();
        }
        return null;
      });
    }
  }

}
