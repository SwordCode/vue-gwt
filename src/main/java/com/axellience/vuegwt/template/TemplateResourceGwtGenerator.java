/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.axellience.vuegwt.template;

import com.axellience.vuegwt.jsr69.component.ComponentJsTypeGenerator;
import com.axellience.vuegwt.template.compiler.VueTemplateCompiler;
import com.axellience.vuegwt.template.compiler.VueTemplateCompilerException;
import com.axellience.vuegwt.template.compiler.VueTemplateCompilerResult;
import com.axellience.vuegwt.template.parser.TemplateParser;
import com.axellience.vuegwt.template.parser.exceptions.TemplateParserException;
import com.axellience.vuegwt.template.parser.result.TemplateExpression;
import com.axellience.vuegwt.template.parser.result.TemplateParserResult;
import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.util.Util;
import com.google.gwt.resources.client.ClientBundle.Source;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.resources.ext.AbstractResourceGenerator;
import com.google.gwt.resources.ext.ResourceContext;
import com.google.gwt.resources.ext.ResourceGeneratorUtil;
import com.google.gwt.resources.ext.SupportsGeneratorResultCaching;
import com.google.gwt.user.rebind.SourceWriter;
import com.google.gwt.user.rebind.StringSourceWriter;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static com.axellience.vuegwt.jsr69.GenerationNameUtil.COMPONENT_JS_TYPE_SUFFIX;
import static com.axellience.vuegwt.jsr69.GenerationNameUtil.STYLE_BUNDLE_METHOD_NAME;
import static com.axellience.vuegwt.jsr69.GenerationNameUtil.styleBundleName;

/**
 * This generator parse and compile the HTML template.
 * The resulting object has information that can be passed to Vue when declaring the component.
 * Original Source: GWT Project http://www.gwtproject.org/
 * <p>
 * Modified by Adrien Baron
 */
public final class TemplateResourceGwtGenerator extends AbstractResourceGenerator
    implements SupportsGeneratorResultCaching
{
    /**
     * Java compiler has a limit of 2^16 bytes for encoding string constants in a
     * class file. Since the max size of a character is 4 bytes, we'll limit the
     * number of characters to (2^14 - 1) to fit within one record.
     */
    private static final int MAX_STRING_CHUNK = 16383;

    @Override
    public String createAssignment(TreeLogger logger, ResourceContext context, JMethod method)
    throws UnableToCompleteException
    {
        URL resource = getResource(logger, context, method);
        SourceWriter sw = new StringSourceWriter();

        TypeOracle typeOracle = context.getGeneratorContext().getTypeOracle();
        String templateResourceTypeName = getTypeName(method) + COMPONENT_JS_TYPE_SUFFIX;

        // No resource for the template
        if (resource == null)
        {
            logger.log(TreeLogger.ERROR,
                "\nCouldn't find template for component \""
                    + getTypeName(method)
                    + "\". If it doesn't have a template please set hasTemplate to false in the @Component annotation.");
            return "";
        }

        try
        {
            // Get template content from HTML file
            String templateContent = Util.readURLAsString(resource);

            // Process it
            TemplateParserResult templateParserResult = new TemplateParser().parseHtmlTemplate(
                templateContent,
                typeOracle.findType(templateResourceTypeName));

            // Convenience when examining the generated code.
            if (!AbstractResourceGenerator.STRIP_COMMENTS)
                sw.println("// " + resource.toExternalForm());

            createTemplateResourceImpl(context,
                method,
                sw,
                templateResourceTypeName,
                templateParserResult);
        }
        catch (TemplateParserException e)
        {
            logger.log(TreeLogger.ERROR, e.getMessage());
        }

        return sw.toString();
    }

    /**
     * Create the template resource implementation based on the result of the template parser.
     * @param context The resource context (used to retrieve resources)
     * @param method The method returning the resource in the resource bundle
     * @param sw The source writer
     * @param templateResourceTypeName The name of our Template resource generated by {@link
     * ComponentJsTypeGenerator}
     * @param templateParserResult The result of the HTML template parsed by {@link TemplateParser}
     * @throws UnableToCompleteException in case it fails to compile the HTML template to a JS
     * render function
     */
    private void createTemplateResourceImpl(ResourceContext context, JMethod method,
        SourceWriter sw, String templateResourceTypeName, TemplateParserResult templateParserResult)
    throws UnableToCompleteException
    {
        // Start class
        sw.println("new " + templateResourceTypeName + "() {");
        sw.indent();

        // Add the get name method
        generateGetName(method, sw);

        // Compile the resulting HTML template String
        compileTemplateString(sw, templateParserResult.getProcessedTemplate(), context);

        // Declare component styles
        processComponentStyles(sw, templateParserResult);

        // Process the java expressions from the template
        processTemplateExpressions(sw, templateParserResult);

        // End class
        sw.outdent();
        sw.println("}");
    }

    /**
     * Get the type name from the HTML template path.
     * @param method The resource method with the @Source annotation
     * @return The full qualified name of the Class
     */
    private String getTypeName(JMethod method)
    {
        Source resourceAnnotation = method.getAnnotation(Source.class);
        String resourcePath = resourceAnnotation.value()[0];
        return resourcePath.substring(0, resourcePath.length() - 5).replaceAll("/", ".");
    }

    /**
     * Compile the HTML template and transform it to a JS render function.
     * @param sw The source writer
     * @param templateString The HTML template string to compile
     */
    private void compileTemplateString(SourceWriter sw, String templateString,
        ResourceContext context) throws UnableToCompleteException
    {
        VueTemplateCompilerResult result;
        try
        {
            VueTemplateCompiler vueTemplateCompiler =
                new VueTemplateCompiler(context.getGeneratorContext().getResourcesOracle());
            result = vueTemplateCompiler.compile(templateString);
        }
        catch (VueTemplateCompilerException e)
        {
            e.printStackTrace();
            throw new UnableToCompleteException();
        }

        generateGetRenderFunction(sw, result);
        generateGetStaticRenderFunctions(sw, result);
    }

    /**
     * Generate the method that returns the body of the render function.
     * @param sw The source writer
     * @param result The result from compilation using vue-template-compiler
     */
    private void generateGetRenderFunction(SourceWriter sw, VueTemplateCompilerResult result)
    {
        sw.println("public String getRenderFunction() {");
        sw.indent();

        String renderFunction = result.getRenderFunction();
        sw.print("return ");
        writeLongString(sw, renderFunction);
        sw.println(";");
        sw.outdent();
        sw.println("}");
    }

    /**
     * Generate the method that returns the body of the static render functions.
     * @param sw The source writer
     * @param result The result from compilation using vue-template-compiler
     */
    private void generateGetStaticRenderFunctions(SourceWriter sw, VueTemplateCompilerResult result)
    {
        sw.println("public String[] getStaticRenderFunctions() {");
        sw.indent();

        sw.println("return new String[] {");
        sw.indent();
        boolean isFirst = true;
        for (String staticRenderFunction : result.getStaticRenderFunctions())
        {
            if (isFirst)
                isFirst = false;
            else
                sw.println(",");

            writeLongString(sw, staticRenderFunction);
        }
        sw.outdent();
        sw.println("};");
        sw.println("}");
    }

    /**
     * Process the expressions found in the HTML template
     * @param sw The source writer
     * @param templateParserResult Result from the parsing of the HTML Template
     */
    private void processTemplateExpressions(SourceWriter sw,
        TemplateParserResult templateParserResult)
    {
        for (TemplateExpression expression : templateParserResult.getExpressions())
        {
            generateTemplateExpressionMethod(sw, expression);
        }

        generateGetTemplateMethods(sw, templateParserResult);
    }

    /**
     * Generate the Java method for an expression in the Template
     * @param sw The source writer
     * @param expression An expression from the HTML template
     */
    private void generateTemplateExpressionMethod(SourceWriter sw, TemplateExpression expression)
    {
        String expressionReturnType = expression.getType();
        if ("VOID".equals(expressionReturnType))
            expressionReturnType = "void";

        jsMethodAnnotation(sw);
        String[] parameters = expression
            .getParameters()
            .stream()
            .map(param -> param.getType() + " " + param.getName())
            .toArray(String[]::new);

        sw.println("public " + expressionReturnType + " " + expression.getId() + "(" + String.join(
            ", ",
            parameters) + ") {");
        sw.indent();

        if (isString(expressionReturnType))
        {
            sw.println("return (" + expression.getBody() + ") + \"\";");
        }
        else if ("void".equals(expressionReturnType))
        {
            sw.println(expression.getBody() + ";");
        }
        else
        {
            sw.println("return (" + expressionReturnType + ") (" + expression.getBody() + ");");
        }

        sw.outdent();
        sw.println("}");
    }

    /**
     * Generate the method to get the list of methods from the template
     * @param sw The source writer
     * @param templateParserResult Result from the parsing of the HTML Template
     */
    private void generateGetTemplateMethods(SourceWriter sw,
        TemplateParserResult templateParserResult)
    {
        sw.println("public String[] getTemplateMethods() {");
        sw.indent();
        sw.println("return new String[] { " + getExpressionsIds(templateParserResult) + " };");
        sw.outdent();
        sw.println("}");
    }

    private String getExpressionsIds(TemplateParserResult templateParserResult)
    {
        return templateParserResult
            .getExpressions()
            .stream()
            .map(expression -> "\"" + expression.getId() + "\"")
            .collect(Collectors.joining(", "));
    }

    /**
     * Generate the getName method of our generator
     * @param method The method we are generating
     * @param sw The source writer
     */
    private void generateGetName(JMethod method, SourceWriter sw)
    {
        sw.println("public String getName() {");
        sw.indent();
        sw.println("return \"" + method.getName() + "\";");
        sw.outdent();
        sw.println("}");
    }

    /**
     * Generate the method returning Styles declared in the template.
     * @param sw The source writer
     * @param templateParserResult Result from the parsing of the HTML Template
     */
    private void processComponentStyles(SourceWriter sw, TemplateParserResult templateParserResult)
    {
        for (Entry<String, String> entry : templateParserResult.getStyleImports().entrySet())
        {
            String styleInstance =
                styleBundleName(entry.getValue()) + ".INSTANCE." + STYLE_BUNDLE_METHOD_NAME + "()";

            jsPropertyAnnotation(sw);
            sw.println("private "
                + entry.getValue()
                + " "
                + entry.getKey()
                + " = "
                + styleInstance
                + ";");
        }

        String mapType =
            Map.class.getCanonicalName() + "<String, " + CssResource.class.getCanonicalName() + ">";
        sw.println("public " + mapType + "getTemplateStyles() { ");
        sw.indent();
        sw.println(mapType + " result = new " + HashMap.class.getCanonicalName() + "<>();");
        for (String styleName : templateParserResult.getStyleImports().keySet())
        {
            sw.println("result.put(\"" + styleName + "\", " + styleName + ");");
        }
        sw.println("return result;");
        sw.outdent();
        sw.println("}");
    }

    /**
     * Check if a given expressionReturnType is String.
     * @param expressionReturnType The return type
     * @return True if this is a String, false otherwise
     */
    private boolean isString(String expressionReturnType)
    {
        return "java.lang.String".equals(expressionReturnType) || "String".equals(
            expressionReturnType);
    }

    /**
     * A single constant that is too long will crash the compiler with an out of
     * memory error. Break up the constant and generate code that appends using a
     * buffer.
     */
    private void writeLongString(SourceWriter sw, String toWrite)
    {
        if (toWrite.length() > MAX_STRING_CHUNK)
        {
            sw.println("new StringBuilder()");
            int offset = 0;
            int length = toWrite.length();
            while (offset < length - 1)
            {
                int subLength = Math.min(MAX_STRING_CHUNK, length - offset);
                sw.print(".append(\"");
                sw.print(Generator.escape(toWrite.substring(offset, offset + subLength)));
                sw.println("\")");
                offset += subLength;
            }
        }
        else
        {
            sw.println("\"" + Generator.escape(toWrite) + "\"");
        }
    }

    private void jsPropertyAnnotation(SourceWriter sw)
    {
        sw.println("@SuppressWarnings(\"unusable-by-js\")");
        sw.println("@jsinterop.annotations.JsProperty");
    }

    private void jsMethodAnnotation(SourceWriter sw)
    {
        sw.println("@SuppressWarnings(\"unusable-by-js\")");
        sw.println("@jsinterop.annotations.JsMethod");
    }

    private URL getResource(TreeLogger logger, ResourceContext context, JMethod method)
    {
        URL[] resources;
        try
        {
            resources = ResourceGeneratorUtil.findResources(logger, context, method);
        }
        catch (UnableToCompleteException e)
        {
            resources = null;
        }

        return resources == null ? null : resources[0];
    }
}
