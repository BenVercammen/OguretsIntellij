package org.jetbrains.plugins.cucumber.dart;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.junit2.info.LocationUtil;
import com.intellij.find.findUsages.JavaFindUsagesHelper;
import com.intellij.find.findUsages.JavaMethodFindUsagesOptions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.CommonProcessors;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.lang.dart.psi.DartMethodDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.MapParameterTypeManager;
import org.jetbrains.plugins.cucumber.dart.steps.reference.CucumberJavaAnnotationProvider;
import org.jetbrains.plugins.cucumber.psi.GherkinFeature;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinScenarioOutline;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.psi.util.PsiTreeUtil.getChildOfType;
import static com.intellij.psi.util.PsiTreeUtil.getChildrenOfTypeAsList;
import static org.jetbrains.plugins.cucumber.CucumberUtil.STANDARD_PARAMETER_TYPES;
import static org.jetbrains.plugins.cucumber.MapParameterTypeManager.DEFAULT;

public class CucumberDartUtil {
  public static final String CUCUMBER_STEP_ANNOTATION_PREFIX_1_0 = "cucumber.annotation.";
  public static final String CUCUMBER_STEP_ANNOTATION_PREFIX_1_1 = "cucumber.api.java.";

  public static final String PARAMETER_TYPE_CLASS = "io.cucumber.cucumberexpressions.ParameterType";

  private static final Map<String, String> DART_PARAMETER_TYPES;
  public static final String CUCUMBER_EXPRESSIONS_CLASS_MARKER = "io.cucumber.cucumberexpressions.CucumberExpressionGenerator";

  private static final Pattern BEGIN_ANCHOR = Pattern.compile("^\\^.*");
  private static final Pattern END_ANCHOR = Pattern.compile(".*\\$$");
  private static final Pattern SCRIPT_STYLE_REGEXP = Pattern.compile("^/(.*)/$");
  private static final Pattern PARENTHESIS = Pattern.compile("\\(([^)]+)\\)");
  private static final Pattern ALPHA = Pattern.compile("[a-zA-Z]+");

  static {
    Map<String, String> dartParameterTypes = new HashMap<>();
    dartParameterTypes.put("int", STANDARD_PARAMETER_TYPES.get("int"));
    dartParameterTypes.put("double", STANDARD_PARAMETER_TYPES.get("float"));

    DART_PARAMETER_TYPES = Collections.unmodifiableMap(dartParameterTypes);
  }

  /**
   * Checks if expression should be considered as a CucumberExpression or as a RegEx
   * @see <a href="http://google.com">https://github.com/cucumber/cucumber/blob/master/cucumber-expressions/java/heuristics.adoc</a>
   */
  public static boolean isCucumberExpression(@NotNull String expression) {
    Matcher m = BEGIN_ANCHOR.matcher(expression);
    if (m.find()) {
      return false;
    }
    m = END_ANCHOR.matcher(expression);
    if (m.find()) {
      return false;
    }
    m = SCRIPT_STYLE_REGEXP.matcher(expression);
    if (m.find()) {
      return false;
    }
    m = PARENTHESIS.matcher(expression);
    if (m.find()) {
      String insideParenthesis = m.group(1);
      if (ALPHA.matcher(insideParenthesis).lookingAt()) {
        return true;
      }
      return false;
    }
    return true;
  }

  private static String getCucumberAnnotationSuffix(@NotNull String name) {
    if (name.startsWith(CUCUMBER_STEP_ANNOTATION_PREFIX_1_0)) {
      return name.substring(CUCUMBER_STEP_ANNOTATION_PREFIX_1_0.length());
    }
    else if (name.startsWith(CUCUMBER_STEP_ANNOTATION_PREFIX_1_1)) {
      return name.substring(CUCUMBER_STEP_ANNOTATION_PREFIX_1_1.length());
    } else {
      return "";
    }
  }

  public static String getCucumberPendingExceptionFqn(@NotNull final PsiElement context) {
    return "PendingException";
  }

  @Nullable
  private static String getAnnotationName(@NotNull final PsiAnnotation annotation) {
    final Ref<String> qualifiedAnnotationName = new Ref<>();
    ApplicationManager.getApplication().runReadAction(() -> {
      String qualifiedName = annotation.getQualifiedName();
      qualifiedAnnotationName.set(qualifiedName);
    }
    );
    return qualifiedAnnotationName.get();
  }

  public static boolean isCucumberStepAnnotation(@NotNull final PsiAnnotation annotation) {
    final String annotationName = getAnnotationName(annotation);
    if (annotationName == null) return false;

    final String annotationSuffix = getCucumberAnnotationSuffix(annotationName);
    if (annotationSuffix.contains(".")) {
      return true;
    }
    return CucumberJavaAnnotationProvider.STEP_MARKERS.contains(annotationName);
  }

  public static boolean isCucumberHookAnnotation(@NotNull final PsiAnnotation annotation) {
    final String annotationName = getAnnotationName(annotation);
    if (annotationName == null) return false;

    final String annotationSuffix = getCucumberAnnotationSuffix(annotationName);
    return CucumberJavaAnnotationProvider.HOOK_MARKERS.contains(annotationSuffix);
  }

  public static boolean isStepDefinition(@NotNull final PsiMethod method) {
    final PsiAnnotation stepAnnotation = getCucumberStepAnnotation(method);
    return stepAnnotation != null && getAnnotationValue(stepAnnotation) != null;
  }

  public static boolean isHook(@NotNull final PsiMethod method) {
    return getCucumberHookAnnotation(method) != null;
  }

  public static boolean isStepDefinitionClass(@NotNull final PsiClass clazz) {
    PsiMethod[] methods = clazz.getAllMethods();
    for (PsiMethod method : methods) {
      if (getCucumberStepAnnotation(method) != null || getCucumberHookAnnotation(method) != null) return true;
    }
    return false;
  }

  public static PsiAnnotation getCucumberStepAnnotation(@NotNull PsiMethod method) {
    return getCucumberStepAnnotation(method, null);
  }

  @Nullable
  public static PsiAnnotation getCucumberStepAnnotation(@NotNull PsiMethod method, @Nullable String annotationClassName) {
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return null;
    }

    final PsiAnnotation[] annotations = method.getModifierList().getAnnotations();

    for (PsiAnnotation annotation : annotations) {
      if (annotation != null &&
          (annotationClassName == null || annotationClassName.equals(annotation.getQualifiedName())) &&
          isCucumberStepAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  /**
   * Computes value of Step Definition Annotation. If {@code annotationClassName provided} value of the annotation with corresponding class
   * will be returned. Operations with string constants handled.
   */
  @Nullable
  public static String getStepAnnotationValue(@NotNull PsiMethod method, @Nullable String annotationClassName) {
    final PsiAnnotation stepAnnotation = getCucumberStepAnnotation(method, annotationClassName);
    if (stepAnnotation == null) {
      return null;
    }

    return getAnnotationValue(stepAnnotation);
  }

  @Nullable
  public static String getAnnotationValue(@NotNull PsiAnnotation stepAnnotation) {
    return AnnotationUtil.getDeclaredStringAttributeValue(stepAnnotation, "value");
  }

  @Nullable
  public static PsiAnnotation getCucumberHookAnnotation(PsiMethod method) {
    if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return null;
    }

    final PsiAnnotation[] annotations = method.getModifierList().getAnnotations();

    for (PsiAnnotation annotation : annotations) {
      if (annotation != null && isCucumberHookAnnotation(annotation)) {
        return annotation;
      }
    }
    return null;
  }

  public static String findDartAnnotationText(DartMethodDeclaration dc) {
    return dc.getMetadataList().stream().filter(meta ->
      CucumberJavaAnnotationProvider.HOOK_MARKERS.contains(meta.getReferenceExpression().getFirstChild().getText()) ||
        CucumberJavaAnnotationProvider.STEP_MARKERS.contains(meta.getReferenceExpression().getFirstChild().getText()))
      .map(meta -> stripQuotes(meta.getReferenceExpression().getNextSibling().getFirstChild().getNextSibling().getText()))
      .findFirst()
      .orElse(null);
  }

  protected static String stripQuotes(String str) {
    if (str.startsWith("\"")) {
      str = str.substring(1);
    }
    if (str.endsWith("\"")) {
      str = str.substring(0, str.length() - 1);
    }

    return str;
  }

  public static String findDartCucumberAnnotation(DartMethodDeclaration dc) {
    return dc.getMetadataList().stream().filter(meta ->
      CucumberJavaAnnotationProvider.HOOK_MARKERS.contains(meta.getReferenceExpression().getFirstChild().getText()) ||
        CucumberJavaAnnotationProvider.STEP_MARKERS.contains(meta.getReferenceExpression().getFirstChild().getText()))
      .map(meta -> meta.getReferenceExpression().getFirstChild().getText())
      .findFirst()
      .orElse(null);
  }

  @Nullable
  public static String getPatternFromStepDefinition(@NotNull final PsiAnnotation stepAnnotation) {
    String result = AnnotationUtil.getStringAttributeValue(stepAnnotation, null);
    if (result != null) {
      result = result.replaceAll("\\\\", "\\\\\\\\");
    }
    return result;
  }

  @Nullable
  private static String getPackageOfStepDef(GherkinStep[] steps) {
    for (GherkinStep step : steps) {
      final String pack = getPackageOfStep(step);
      if (pack != null) return pack;
    }
    return null;
  }

  @NotNull
  public static String getPackageOfStepDef(final PsiElement element) {
    PsiFile file = element.getContainingFile();
    if (file instanceof GherkinFile) {
      GherkinFeature feature = getChildOfType(file, GherkinFeature.class);
      if (feature != null) {
        List<GherkinScenario> scenarioList = getChildrenOfTypeAsList(feature, GherkinScenario.class);
        for (GherkinScenario scenario : scenarioList) {
          String result = getPackageOfStepDef(scenario.getSteps());
          if (result != null) {
            return result;
          }
        }

        List<GherkinScenarioOutline> scenarioOutlineList = getChildrenOfTypeAsList(feature, GherkinScenarioOutline.class);
        for (GherkinScenarioOutline scenario : scenarioOutlineList) {
          String result = getPackageOfStepDef(scenario.getSteps());
          if (result != null) {
            return result;
          }
        }
      }
    }
    return "";
  }

  public static String getPackageOfStep(GherkinStep step) {
    for (PsiReference ref : step.getReferences()) {
      PsiElement refElement = ref.resolve();
      if (refElement instanceof PsiMethod || refElement instanceof PsiMethodCallExpression) {
        PsiClassOwner file = (PsiClassOwner)refElement.getContainingFile();
        final String packageName = file.getPackageName();
        if (StringUtil.isNotEmpty(packageName)) {
          return packageName;
        }
      }
    }
    return null;
  }

  public static void addGlue(String glue, Set<String> glues) {
    boolean covered = false;
    final Set<String> toRemove = ContainerUtil.newHashSet();
    for (String existedGlue : glues) {
      if (glue.startsWith(existedGlue + ".")) {
        covered = true;
        break;
      }
      else if (existedGlue.startsWith(glue + ".")) {
        toRemove.add(existedGlue);
      }
    }

    for (String removing : toRemove) {
      glues.remove(removing);
    }

    if (!covered) {
      glues.add(glue);
    }
  }

  public static MapParameterTypeManager getAllParameterTypes(@NotNull Module module) {
    Project project = module.getProject();
    PsiManager manager = PsiManager.getInstance(project);

    VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
    PsiDirectory psiDirectory = projectDir != null ? manager.findDirectory(projectDir) : null;
    if (psiDirectory != null) {
      return CachedValuesManager.getCachedValue(psiDirectory, () ->
        CachedValueProvider.Result.create(doGetAllParameterTypes(module), PsiModificationTracker.MODIFICATION_COUNT));
    }

    return DEFAULT;
  }

  @NotNull
  private static MapParameterTypeManager doGetAllParameterTypes(@NotNull Module module) {
    final GlobalSearchScope dependenciesScope = module.getModuleWithDependenciesAndLibrariesScope(true);
    CommonProcessors.CollectProcessor<UsageInfo> processor = new CommonProcessors.CollectProcessor<>();
    JavaMethodFindUsagesOptions options = new JavaMethodFindUsagesOptions(dependenciesScope);

    PsiClass parameterTypeClass = ClassUtil.findPsiClass(PsiManager.getInstance(module.getProject()), PARAMETER_TYPE_CLASS);
    if (parameterTypeClass != null) {
      for (PsiMethod constructor: parameterTypeClass.getConstructors()) {
        JavaFindUsagesHelper.processElementUsages(constructor, options, processor);
      }
    }

    SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(module.getProject());
    Map<String, String> values = new HashMap<>();
    Map<String, SmartPsiElementPointer<PsiElement>> declarations = new HashMap<>();
    for (UsageInfo ui: processor.getResults()) {
      PsiElement element = ui.getElement();
      if (element != null && element.getParent() instanceof PsiNewExpression) {
        PsiNewExpression newExpression = (PsiNewExpression)element.getParent();
        PsiExpressionList arguments = newExpression.getArgumentList();
        if (arguments != null) {
          PsiExpression[] expressions = arguments.getExpressions();
          if (expressions.length > 1) {
            PsiConstantEvaluationHelper evaluationHelper = JavaPsiFacade.getInstance(module.getProject()).getConstantEvaluationHelper();

            Object constantValue = evaluationHelper.computeConstantExpression(expressions[0], false);
            if (constantValue == null) {
              continue;
            }
            String name = constantValue.toString();

            constantValue = evaluationHelper.computeConstantExpression(expressions[1], false);
            if (constantValue == null) {
              continue;
            }
            String value = constantValue.toString();
            values.put(name, value);

            SmartPsiElementPointer<PsiElement> smartPointer = smartPointerManager.createSmartPsiElementPointer(expressions[0]);
            declarations.put(name, smartPointer);
          }
        }
      }
    }

    values.putAll(STANDARD_PARAMETER_TYPES);
    values.putAll(DART_PARAMETER_TYPES);
    return new MapParameterTypeManager(values, declarations);
  }

  /**
   * Checks if library with CucumberExpressions library attached to the project.
   * @return true if step definitions should be written in Cucumber Expressions (since Cucumber v 3.0),
   * false in case of old-style Regexp step definitions.
   */
  public static boolean isCucumberExpressionsAvailable(@NotNull PsiElement context) {
    return true;
  }
}
