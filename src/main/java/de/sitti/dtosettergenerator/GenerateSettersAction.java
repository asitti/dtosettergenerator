package de.sitti.dtosettergenerator;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class GenerateSettersAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Immer aktiviert lassen
        e.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || psiFile == null) return;

        // Element am Cursor finden
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null) {
            showMessage(project, "Error", "No element found at cursor position");
            return;
        }

        // Versuche, eine Variable zu finden
        PsiVariable variable = findDtoVariable(element);
        if (variable == null) {
            showMessage(project, "No DTO Found",
                    "Please place the cursor on a DTO variable (e.g., VertreterDTO vertreterDTO)");
            return;
        }

        // Überprüfe, ob es sich um eine DTO-Klasse handelt
        PsiClass psiClass = PsiTypesUtil.getPsiClass(variable.getType());
        if (psiClass == null || !isDtoClass(psiClass)) {
            showMessage(project, "Not a DTO",
                    "The selected variable is not a DTO class (should end with 'DTO')");
            return;
        }

        // Finde die Anweisung, die die Variable enthält
        PsiStatement statement = findContainingStatement(variable, element);
        if (statement == null) {
            showMessage(project, "Error", "Could not find the statement containing the variable");
            return;
        }

        // Generiere den Code manuell und direkt
        generateCodeAndInsert(project, psiFile, statement, psiClass, variable.getName());
    }

    // Komplett neue Implementation mit manueller Code-Generierung
    private void generateCodeAndInsert(Project project, PsiFile psiFile, PsiStatement statement,
                                       PsiClass rootDtoClass, String rootVarName) {

        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                // Sammle alle DTOs und ihre Beziehungen
                Map<String, String> dtoVars = new HashMap<>();  // className -> varName
                List<String> codeLines = new ArrayList<>();
                Set<String> importsNeeded = new HashSet<>();

                // Die Wurzel-DTO ist bereits deklariert, also nicht hinzufügen
                dtoVars.put(rootDtoClass.getQualifiedName(), rootVarName);

                // Finde alle verschachtelten DTOs
                Map<String, PsiClass> nestedDtos = findNestedDtos(rootDtoClass, new HashMap<>());

                // Erstelle für jede gefundene DTO eine Variable (außer für die Wurzel)
                for (Map.Entry<String, PsiClass> entry : nestedDtos.entrySet()) {
                    String className = entry.getKey();
                    PsiClass dtoClass = entry.getValue();
                    // Überspringen, wenn diese DTO bereits eine Variable hat
                    if (dtoVars.containsKey(className)) continue;

                    // Variable-Name aus Klassennamen erstellen
                    String simpleName = dtoClass.getName();
                    String varName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
                    dtoVars.put(className, varName);

                    // Variablendeklaration hinzufügen
                    codeLines.add(simpleName + " " + varName + " = new " + simpleName + "();");
                }

                // Generiere alle direkten Setter für alle DTOs
                for (Map.Entry<String, PsiClass> entry : nestedDtos.entrySet()) {
                    PsiClass dtoClass = entry.getValue();
                    String varName = dtoVars.get(entry.getKey());

                    generateDirectSetters(dtoClass, varName, codeLines, importsNeeded);
                }

                // Auch für die Wurzel-DTO die direkten Setter generieren
                generateDirectSetters(rootDtoClass, rootVarName, codeLines, importsNeeded);

                // Generiere alle Referenz-Setter
                for (Map.Entry<String, PsiClass> entry : nestedDtos.entrySet()) {
                    PsiClass dtoClass = entry.getValue();
                    String varName = dtoVars.get(entry.getKey());

                    generateReferenceSetters(dtoClass, varName, dtoVars, codeLines);
                }

                // Auch für die Wurzel-DTO die Referenz-Setter generieren
                generateReferenceSetters(rootDtoClass, rootVarName, dtoVars, codeLines);

                // Füge Imports hinzu
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                    for (String importClass : importsNeeded) {
                        if (!hasImport(javaFile, importClass)) {
                            PsiClass importedClass = JavaPsiFacade.getInstance(project).findClass(
                                    importClass, GlobalSearchScope.allScope(project));
                            if (importedClass != null) {
                                javaFile.getImportList().add(factory.createImportStatement(importedClass));
                            }
                        }
                    }
                }

                // Füge Code ein
                Document document = psiFile.getViewProvider().getDocument();
                if (document != null) {
                    StringBuilder codeToInsert = new StringBuilder();
                    for (String line : codeLines) {
                        codeToInsert.append(line).append("\n");
                    }

                    int statementOffset = statement.getTextRange().getEndOffset();
                    document.insertString(statementOffset, "\n" + codeToInsert);

                    // Formatiere Code
                    PsiDocumentManager.getInstance(project).commitDocument(document);
                    CodeStyleManager.getInstance(project).reformat(psiFile);
                }

            } catch (Exception ex) {
                showMessage(project, "Error", "Failed to generate code: " + ex.getMessage());
            }
        });
    }

    private Map<String, PsiClass> findNestedDtos(PsiClass dtoClass, Map<String, PsiClass> result) {
        // Füge diese DTO zur Map hinzu
        result.put(dtoClass.getQualifiedName(), dtoClass);

        // Suche in allen Feldern nach weiteren DTOs
        for (PsiField field : dtoClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            PsiClass fieldClass = PsiTypesUtil.getPsiClass(field.getType());
            if (fieldClass != null && isDtoClass(fieldClass) &&
                    !result.containsKey(fieldClass.getQualifiedName())) {
                // Rekursiv weitere verschachtelte DTOs finden
                findNestedDtos(fieldClass, result);
            }
        }

        return result;
    }

    private void generateDirectSetters(PsiClass dtoClass, String varName,
                                       List<String> codeLines, Set<String> importsNeeded) {
        for (PsiField field : dtoClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            String fieldName = field.getName();
            if (fieldName == null) continue;

            // Setter-Namen erstellen
            String capitalizedFieldName = fieldName.substring(0, 1).toUpperCase() +
                    (fieldName.length() > 1 ? fieldName.substring(1) : "");
            String setterName = "set" + capitalizedFieldName;

            // Überprüfen, ob die Setter-Methode existiert
            PsiMethod[] methods = dtoClass.findMethodsByName(setterName, true);
            if (methods.length > 0) {
                PsiMethod setter = methods[0];
                PsiParameter[] parameters = setter.getParameterList().getParameters();

                if (parameters.length > 0) {
                    PsiType paramType = parameters[0].getType();
                    PsiClass paramClass = PsiTypesUtil.getPsiClass(paramType);

                    // Nur direkte Setter (nicht für DTO-Referenzen)
                    if (paramClass == null || !isDtoClass(paramClass)) {
                        String defaultValue = getDefaultValueForType(paramType);

                        // Import-Anforderungen für spezielle Typen
                        if (paramType.getCanonicalText().equals("java.time.LocalDate")) {
                            importsNeeded.add("java.time.LocalDate");
                            defaultValue = "LocalDate.now()";
                        } else if (paramType.getCanonicalText().equals("java.time.LocalDateTime")) {
                            importsNeeded.add("java.time.LocalDateTime");
                            defaultValue = "LocalDateTime.now()";
                        } else if (paramType.getCanonicalText().equals("java.util.Date")) {
                            importsNeeded.add("java.util.Date");
                            defaultValue = "new Date()";
                        } else if (paramType.getCanonicalText().startsWith("java.util.List")) {
                            importsNeeded.add("java.util.ArrayList");
                            defaultValue = "new ArrayList<>()";
                        } else if (paramType.getCanonicalText().startsWith("java.util.Set")) {
                            importsNeeded.add("java.util.HashSet");
                            defaultValue = "new HashSet<>()";
                        } else if (paramType.getCanonicalText().startsWith("java.util.Map")) {
                            importsNeeded.add("java.util.HashMap");
                            defaultValue = "new HashMap<>()";
                        }

                        // Setter-Aufruf hinzufügen
                        codeLines.add(varName + "." + setterName + "(" + defaultValue + ");");
                    }
                } else {
                    // Parameterloser Setter
                    codeLines.add(varName + "." + setterName + "();");
                }
            }
        }
    }

    private void generateReferenceSetters(PsiClass dtoClass, String varName,
                                          Map<String, String> dtoVars, List<String> codeLines) {
        for (PsiField field : dtoClass.getAllFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.FINAL)) {
                continue;
            }

            String fieldName = field.getName();
            if (fieldName == null) continue;

            // Setter-Namen erstellen
            String capitalizedFieldName = fieldName.substring(0, 1).toUpperCase() +
                    (fieldName.length() > 1 ? fieldName.substring(1) : "");
            String setterName = "set" + capitalizedFieldName;

            // Überprüfen, ob die Setter-Methode existiert
            PsiMethod[] methods = dtoClass.findMethodsByName(setterName, true);
            if (methods.length > 0) {
                PsiMethod setter = methods[0];
                PsiParameter[] parameters = setter.getParameterList().getParameters();

                if (parameters.length > 0) {
                    PsiType paramType = parameters[0].getType();
                    PsiClass paramClass = PsiTypesUtil.getPsiClass(paramType);

                    // Nur Setter für DTO-Referenzen
                    if (paramClass != null && isDtoClass(paramClass)) {
                        String paramClassName = paramClass.getQualifiedName();
                        if (dtoVars.containsKey(paramClassName)) {
                            String paramVarName = dtoVars.get(paramClassName);
                            codeLines.add(varName + "." + setterName + "(" + paramVarName + ");");
                        }
                    }
                }
            }
        }
    }

    private String getDefaultValueForType(PsiType type) {
        String typeName = type.getCanonicalText();

        // Primitive Typen
        if (typeName.equals("int") || typeName.equals("java.lang.Integer")) return "0";
        if (typeName.equals("long") || typeName.equals("java.lang.Long")) return "0L";
        if (typeName.equals("float") || typeName.equals("java.lang.Float")) return "0.0f";
        if (typeName.equals("double") || typeName.equals("java.lang.Double")) return "0.0";
        if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) return "false";
        if (typeName.equals("char") || typeName.equals("java.lang.Character")) return "'a'";
        if (typeName.equals("byte") || typeName.equals("java.lang.Byte")) return "(byte)0";
        if (typeName.equals("short") || typeName.equals("java.lang.Short")) return "(short)0";

        // String
        if (typeName.equals("java.lang.String")) return "\"\"";

        // Imports werden separat in generateDirectSetters behandelt

        // Fallback für alle anderen Typen
        return "null";
    }

    private PsiVariable findDtoVariable(PsiElement element) {
        PsiLocalVariable localVar = PsiTreeUtil.getParentOfType(element, PsiLocalVariable.class);
        if (localVar != null) return localVar;

        PsiReferenceExpression refExpr = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression.class);
        if (refExpr != null) {
            PsiElement resolved = refExpr.resolve();
            if (resolved instanceof PsiVariable) {
                return (PsiVariable) resolved;
            }
        }

        return null;
    }

    private boolean isDtoClass(PsiClass psiClass) {
        String className = psiClass.getName();
        return className != null && className.endsWith("DTO");
    }

    private PsiStatement findContainingStatement(PsiVariable variable, PsiElement element) {
        if (variable instanceof PsiLocalVariable) {
            return PsiTreeUtil.getParentOfType(variable, PsiStatement.class);
        }

        return PsiTreeUtil.getParentOfType(element, PsiStatement.class);
    }

    private boolean hasImport(PsiJavaFile file, String qualifiedName) {
        PsiImportList importList = file.getImportList();
        if (importList == null) return false;

        for (PsiImportStatement importStatement : importList.getImportStatements()) {
            if (importStatement.getQualifiedName() != null &&
                    importStatement.getQualifiedName().equals(qualifiedName)) {
                return true;
            }
        }

        return false;
    }

    private void showMessage(Project project, String title, String message) {
        Messages.showMessageDialog(project, message, title, Messages.getInformationIcon());
    }
}