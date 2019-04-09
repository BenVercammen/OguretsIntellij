package cd.connect.idea.plugins.cucumber.dart.steps.run;

import com.jetbrains.lang.dart.ide.runner.server.DartCommandLineRunnerParameters;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CucumberDartRunnerParameters extends DartCommandLineRunnerParameters implements Cloneable {
  @NotNull
  private Scope myScope = Scope.FEATURE;
  @Nullable private String testRunnerOptions = null;
  @Nullable private String nameFilter = null;
  @Nullable private String cucumberFilePath = null;
  @Nullable private String dartFilePath = null;
  private boolean flutterEnabled = false;
  private int flutterObservatoryPort = 8888;
  private TestType testType;

  @NotNull
  public Scope getScope() {
    return myScope;
  }

  public void setScope(final Scope scope) {
    if (scope != null) { // null in case of corrupted storage
      myScope = scope;
    }
  }

  @Nullable
  public String getCucumberFilePath() {
    return cucumberFilePath;
  }

  public void setCucumberFilePath(@Nullable String cucumberFilePath) {
    this.cucumberFilePath = cucumberFilePath;
  }

  @Nullable
  public String getTestRunnerOptions() {
    return testRunnerOptions;
  }

  @Nullable
  public String getDartFilePath() {
    return dartFilePath;
  }

  public void setDartFilePath(@Nullable String dartFilePath) {
    this.dartFilePath = dartFilePath;
  }

  public void setTestRunnerOptions(@Nullable String testRunnerOptions) {
    this.testRunnerOptions = testRunnerOptions;
  }

  @Nullable
  public String getNameFilter() {
    return nameFilter;
  }

  public void setNameFilter(@Nullable String nameFilter) {
    this.nameFilter = nameFilter;
  }

  public boolean isFlutterEnabled() {
    return flutterEnabled;
  }

  public void setFlutterEnabled(boolean flutterEnabled) {
    this.flutterEnabled = flutterEnabled;
  }

  public int getFlutterObservatoryPort() {
    return flutterObservatoryPort;
  }

  public void setFlutterObservatoryPort(int flutterObservatoryPort) {
    this.flutterObservatoryPort = flutterObservatoryPort;
  }

  public TestType getTestType() {
    return testType;
  }

  public void setTestType(TestType testType) {
    this.testType = testType;
  }

  public enum Scope {
    FOLDER("All in folder"),
    FEATURE("All scenarioes in feature file"),
    SCENARIO("Group or test by name"),
    MULTIPLE_NAMES("Multiple names"); // Used by test re-runner action; not visible in UI

    private final String myPresentableName;

    Scope(final String name) {
      myPresentableName = name;
    }

    public String getPresentableName() {
      return myPresentableName;
    }
  }

  // difference important because of which directory they are in and what kind of runner we use.
  public enum TestType {
    Test, Integration
  }
}