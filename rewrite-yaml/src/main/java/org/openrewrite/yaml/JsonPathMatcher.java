/*
 *  Copyright 2021 the original author or authors.
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  https://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openrewrite.yaml;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.yaml.internal.grammar.*;
import org.openrewrite.yaml.tree.Yaml;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.disjoint;

/**
 * Provides methods for matching the given cursor location to a specified JsonPath expression.
 * <p>
 * This is not a full implementation of the JsonPath syntax as linked in the "see also."
 *
 * @see <a href="https://support.smartbear.com/alertsite/docs/monitors/api/endpoint/jsonpath.html">https://support.smartbear.com/alertsite/docs/monitors/api/endpoint/jsonpath.html</a>
 */
public class JsonPathMatcher {

    private final String jsonPath;

    public JsonPathMatcher(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    public <T> Optional<T> find(Cursor cursor) {
        LinkedList<Tree> cursorPath = cursor.getPathAsStream()
                .filter(o -> o instanceof Tree)
                .map(Tree.class::cast)
                .collect(Collectors.toCollection(LinkedList::new));
        if (cursorPath.isEmpty()) {
            return Optional.empty();
        }
        Collections.reverse(cursorPath);

        Tree start;
        if (jsonPath.startsWith(".") && !jsonPath.startsWith("..")) {
            start = cursor.getValue();
        } else {
            start = cursorPath.peekFirst();
        }
        @SuppressWarnings("ConstantConditions") JsonPathParserVisitor<Object> v = new JsonPathMatcher.JsonPathYamlVisitor(cursorPath, start, false);
        JsonPathParser.JsonPathContext ctx = jsonPath().jsonPath();
        Object result = v.visit(ctx);

        //noinspection unchecked
        return Optional.ofNullable((T) result);
    }

    public boolean matches(Cursor cursor) {
        List<Object> cursorPath = cursor.getPathAsStream().collect(Collectors.toList());
        return find(cursor).map(o -> {
            if (o instanceof List) {
                //noinspection unchecked
                List<Object> l = (List<Object>) o;
                return !disjoint(l, cursorPath) && l.contains(cursor.getValue());
            } else {
                return Objects.equals(o, cursor.getValue());
            }
        }).orElse(false);
    }

    private JsonPathParser jsonPath() {
        return new JsonPathParser(new CommonTokenStream(new JsonPathLexer(CharStreams.fromString(this.jsonPath))));
    }

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    private static class JsonPathYamlVisitor extends JsonPathParserBaseVisitor<Object> {

        private final List<Tree> cursorPath;
        protected Object scope;
        private final boolean isRecursiveDescent;

        public JsonPathYamlVisitor(List<Tree> cursorPath, Object scope, boolean isRecursiveDescent) {
            this.cursorPath = cursorPath;
            this.scope = scope;
            this.isRecursiveDescent = isRecursiveDescent;
        }

        @Override
        protected Object defaultResult() {
            return scope;
        }

        @Override
        protected Object aggregateResult(Object aggregate, Object nextResult) {
            return (scope = nextResult);
        }

        @Override
        public Object visitJsonPath(JsonPathParser.JsonPathContext ctx) {
            if (ctx.ROOT() != null || "[".equals(ctx.start.getText())) {
                scope = cursorPath.stream()
                        .filter(t -> t instanceof Yaml.Mapping)
                        .findFirst()
                        .orElseGet(() -> cursorPath.stream()
                                .filter(t -> t instanceof Yaml.Document && ((Yaml.Document) t).getBlock() instanceof Yaml.Mapping)
                                .map(t -> ((Yaml.Document) t).getBlock())
                                .findFirst()
                                .orElse(null));
            }
            return super.visitJsonPath(ctx);
        }

        @Override
        public Object visitRecursiveDecent(JsonPathParser.RecursiveDecentContext ctx) {
            if (scope == null) {
                return null;
            }

            Object result = null;
            // A recursive descent at the start of the expression or declared in a filter must check the entire cursor patch.
            // `$..foo` or `$.foo..bar[?($..buz == 'buz')]`
            List<ParseTree> previous = ctx.getParent().getParent().children;
            ParserRuleContext current = ctx.getParent();
            if (previous.indexOf(current) - 1 < 0 || "$".equals(previous.get(previous.indexOf(current) - 1).getText())) {
                List<Object> results = new ArrayList<>();
                for (Tree path : cursorPath) {
                    JsonPathMatcher.JsonPathYamlVisitor v = new JsonPathMatcher.JsonPathYamlVisitor(cursorPath, path, false);
                    for (int i = 1; i < ctx.getChildCount(); i++) {
                        result = v.visit(ctx.getChild(i));
                        if (result != null) {
                            results.add(result);
                        }
                    }
                }
                return results;
                // Otherwise, the recursive descent is scoped to the previous match. `$.foo..['find-in-foo']`.
            } else {
                JsonPathMatcher.JsonPathYamlVisitor v = new JsonPathMatcher.JsonPathYamlVisitor(cursorPath, scope, true);
                for (int i = 1; i < ctx.getChildCount(); i++) {
                    result = v.visit(ctx.getChild(i));
                    if (result != null) {
                        break;
                    }
                }
            }
            return result;
        }

        @Override
        public Object visitBracketOperator(JsonPathParser.BracketOperatorContext ctx) {
            if (!ctx.property().isEmpty()) {
                if (ctx.property().size() == 1) {
                    return visitProperty(ctx.property(0));
                }

                // Return a list if more than 1 property is specified.
                return ctx.property().stream()
                        .map(this::visitProperty)
                        .collect(Collectors.toList());
            } else if (ctx.slice() != null) {
                return visitSlice(ctx.slice());
            } else if (ctx.indexes() != null) {
                return visitIndexes(ctx.indexes());
            } else if (ctx.filter() != null) {
                return visitFilter(ctx.filter());
            }

            return null;
        }

        @Override
        public Object visitSlice(JsonPathParser.SliceContext ctx) {
            List<Yaml> results;
            if (scope instanceof List) {
                //noinspection unchecked
                results = (List<Yaml>) scope;
            } else if (scope instanceof Yaml.Sequence) {
                Yaml.Sequence array = (Yaml.Sequence) scope;
                results = new ArrayList<>(array.getEntries());
            } else if (scope instanceof Yaml.Mapping.Entry) {
                scope = ((Yaml.Mapping.Entry) scope).getValue();
                return visitSlice(ctx);
            } else {
                results = new ArrayList<>();
            }

            // A wildcard will use these initial values, so it is not checked in the conditions.
            int start = 0;
            int limit = Integer.MAX_VALUE;

            if (ctx.PositiveNumber() != null) {
                // [:n], Selects the first n elements of the array.
                limit = Integer.parseInt(ctx.PositiveNumber().getText());
            } else if (ctx.NegativeNumber() != null) {
                // [-n:], Selects the last n elements of the array.
                start = results.size() + Integer.parseInt(ctx.NegativeNumber().getText());
            } else if (ctx.start() != null) {
                // [start:end] or [start:]
                // Selects array elements from the start index and up to, but not including, end index.
                // If end is omitted, selects all elements from start until the end of the array.
                start = ctx.start() != null ? Integer.parseInt(ctx.start().getText()) : 0;
                limit = ctx.end() != null ? Integer.parseInt(ctx.end().getText()) + 1 : limit;
            }

            return results.stream()
                    .skip(start)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public Object visitIndexes(JsonPathParser.IndexesContext ctx) {
            List<Object> results;
            if (scope instanceof List) {
                //noinspection unchecked
                results = (List<Object>) scope;
            } else if (scope instanceof Yaml.Sequence) {
                Yaml.Sequence array = (Yaml.Sequence) scope;
                results = new ArrayList<>(array.getEntries());
            } else if (scope instanceof Yaml.Mapping.Entry) {
                scope = ((Yaml.Mapping.Entry) scope).getValue();
                return visitIndexes(ctx);
            } else {
                results = new ArrayList<>();
            }

            List<Object> indexes = new ArrayList<>();
            for (TerminalNode terminalNode : ctx.PositiveNumber()) {
                for (int i = 0; i < results.size(); i++) {
                    if (terminalNode.getText().contains(String.valueOf(i))) {
                        indexes.add(results.get(i));
                    }
                }
            }

            return getResultFromList(indexes);
        }

        @Override
        public Object visitProperty(JsonPathParser.PropertyContext ctx) {
            if (scope instanceof Yaml.Mapping) {
                Yaml.Mapping mapping = (Yaml.Mapping) scope;
                if (isRecursiveDescent) {
                    scope = mapping.getEntries();
                    Object result = getResultFromList(visitProperty(ctx));
                    return getResultFromList(result);
                } else {
                    for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
                        if (entry instanceof Yaml.Mapping.Entry) {
                            String key = entry.getKey().getValue();
                            String name = ctx.StringLiteral() != null ? unquoteStringLiteral(ctx.StringLiteral().getText()) : ctx.Identifier().getText();
                            if (key.equals(name)) {
                                return entry;
                            }
                        }
                    }
                }
            } else if (scope instanceof Yaml.Mapping.Entry) {
                Yaml.Mapping.Entry member = (Yaml.Mapping.Entry) scope;

                List<Object> matches = new ArrayList<>();
                String key = member.getKey().getValue();
                String name = ctx.StringLiteral() != null ? unquoteStringLiteral(ctx.StringLiteral().getText()) : ctx.Identifier().getText();
                if (isRecursiveDescent) {
                    if (key.equals(name)) {
                        matches.add(member);
                    }
                    if (!(member.getValue() instanceof Yaml.Scalar)) {
                        scope = member.getValue();
                        Object result = getResultFromList(visitProperty(ctx));
                        if (result != null) {
                            matches.add(result);
                        }
                    }
                    return getResultFromList(matches);
                } else if (((member.getValue() instanceof Yaml.Scalar))) {
                    return key.equals(name) ? member : null;
                }

                scope = member.getValue();
                return visitProperty(ctx);
            } else if (scope instanceof Yaml.Sequence) {
                Object matches = ((Yaml.Sequence) scope).getEntries().stream()
                        .map(o -> {
                            scope = o;
                            return visitProperty(ctx);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                return getResultFromList(matches);
            } else if (scope instanceof Yaml.Sequence.Entry) {
                Yaml.Sequence.Entry entry = (Yaml.Sequence.Entry) scope;
                scope = entry.getBlock();
                return visitProperty(ctx);
            } else if (scope instanceof List) {
                List<Object> results = ((List<Object>) scope).stream()
                        .map(o -> {
                            scope = o;
                            return visitProperty(ctx);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                // Unwrap lists of results from visitProperty to match the position of the cursor.
                List<Object> matches = new ArrayList<>();
                for (Object result : results) {
                    if (result instanceof List) {
                        matches.addAll(((List<Object>) result));
                    } else {
                        matches.add(result);
                    }
                }

                return getResultFromList(matches);
            }

            return null;
        }

        @Override
        public Object visitWildcard(JsonPathParser.WildcardContext ctx) {
            if (scope instanceof Yaml.Mapping) {
                Yaml.Mapping mapping = (Yaml.Mapping) scope;
                return mapping.getEntries();
            } else if (scope instanceof Yaml.Mapping.Entry) {
                Yaml.Mapping.Entry member = (Yaml.Mapping.Entry) scope;
                return member.getValue();
            } else if (scope instanceof Yaml.Sequence) {
                Object matches = ((Yaml.Sequence) scope).getEntries().stream()
                        .map(o -> {
                            scope = o;
                            return visitWildcard(ctx);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                return getResultFromList(matches);
            } else if (scope instanceof Yaml.Sequence.Entry) {
                System.out.println("here");
            } else if (scope instanceof List) {
                List<Object> results = ((List<Object>) scope).stream()
                        .map(o -> {
                            scope = o;
                            return visitWildcard(ctx);
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // Unwrap lists of results from visitProperty to match the position of the cursor.
                List<Object> matches = new ArrayList<>();
                for (Object result : results) {
                    if (result instanceof List) {
                        matches.addAll(((List<Object>) result));
                    } else {
                        matches.add(result);
                    }
                }

                return getResultFromList(matches);
            }

            return null;
        }

        @Override
        public Object visitUnaryExpression(JsonPathParser.UnaryExpressionContext ctx) {
            if (ctx.Identifier() != null) {
                if (scope instanceof Yaml.Mapping) {
                    scope = ((Yaml.Mapping) scope).getEntries();
                    return visitUnaryExpression(ctx);
                } else if (scope instanceof Yaml.Mapping.Entry) {
                    Yaml.Mapping.Entry entry = (Yaml.Mapping.Entry) scope;
                    String key = entry.getKey().getValue();
                    String identifier = ctx.Identifier().getText();
                    if (key.equals(identifier)) {
                        return entry;
                    }
                    scope = entry.getValue();
                    return getResultFromList(visitUnaryExpression(ctx));
                } else if (scope instanceof Yaml.Sequence) {
                    scope = ((Yaml.Sequence) scope).getEntries();
                    return visitUnaryExpression(ctx);
                } else if (scope instanceof Yaml.Sequence.Entry) {
                    // Unary operators set the scope of the matched key within a block.
                    Yaml.Sequence.Entry entry = (Yaml.Sequence.Entry) scope;
                    scope = entry.getBlock();
                    Object result = visitUnaryExpression(ctx);
                    if (result != null) {
                        return getResultFromList(entry.getBlock());
                    }
                } else if (scope instanceof List) {
                    List<Object> results = ((List<Object>) scope).stream()
                            .map(o -> {
                                scope = o;
                                return visitUnaryExpression(ctx);
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    // Unwrap lists of results from visitProperty to match the position of the cursor.
                    List<Object> matches = new ArrayList<>();
                    for (Object result : results) {
                        if (result instanceof List) {
                            matches.addAll(((List<Object>) result));
                        } else {
                            matches.add(result);
                        }
                    }

                    return getResultFromList(matches);
                }
            } else if (ctx.jsonPath() != null) {
                Object result = getValue(visit(ctx.jsonPath()));
                result = getResultByKey(result, ctx.stop.getText());
                return result;
            }
            return null;
        }

        @Override
        public Object visitRegexExpression(JsonPathParser.RegexExpressionContext ctx) {
            if (scope == null || scope instanceof List && ((List<Object>) scope).isEmpty()) {
                return null;
            }

            Object rhs = ctx.REGEX().getText();
            Object lhs;
            if (scope instanceof Yaml.Scalar) {
                lhs = getValue(scope);
            } else if (scope instanceof Yaml.Mapping.Entry) {
                scope = ((Yaml.Mapping.Entry) scope).getValue();
                return visitRegexExpression(ctx);
            } else {
                lhs = getValue(visitProperty(ctx.property()));
            }

            if (lhs instanceof Yaml.Mapping.Entry) {
                if (((Yaml.Mapping.Entry) lhs).getValue() instanceof Yaml.Scalar) {
                    lhs = getValue(lhs);
                }
            }

            if (lhs != null) {
                String lhStr = lhs.toString();
                String rhStr = rhs.toString();
                if (Pattern.compile(rhStr).matcher(lhStr).matches()) {
                    return scope;
                }
            }
            return null;
        }

        @Override
        public Object visitBinaryExpression(JsonPathParser.BinaryExpressionContext ctx) {
            Object lhs = ctx.children.get(0);
            Object rhs = ctx.children.get(2);

            lhs = getBinaryExpressionResult(lhs);
            rhs = getBinaryExpressionResult(rhs);

            if (ctx.LOGICAL_OPERATOR() != null) {
                throw new UnsupportedOperationException("Logical operators are not supported. Please open an issue if you need this functionality.");
            } else if (ctx.EQUALITY_OPERATOR() != null) {
                String operator;
                switch (ctx.EQUALITY_OPERATOR().getText()) {
                    case ("=="):
                        operator = "==";
                        break;
                    case ("!="):
                        operator = "!=";
                        break;
                    default:
                        return false;
                }

                if (lhs instanceof List) {
                    for (Object match : ((List<Object>) lhs)) {
                        if (match instanceof Yaml.Mapping) {
                            Yaml.Mapping mapping = (Yaml.Mapping) match;
                            for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
                                if (entry.getValue() instanceof Yaml.Scalar &&
                                        checkObjectEquality(((Yaml.Scalar) entry.getValue()).getValue(), rhs, operator)) {
                                    return mapping;
                                }
                            }
                        } else if (match instanceof Yaml.Mapping.Entry) {
                            Yaml.Mapping.Entry entry = (Yaml.Mapping.Entry) match;
                            if (entry.getValue() instanceof Yaml.Scalar &&
                                    checkObjectEquality(((Yaml.Scalar) entry.getValue()).getValue(), rhs, operator)) {
                                return entry;
                            }
                        }
                    }
                } else if (lhs instanceof Yaml.Mapping) {
                    Yaml.Mapping mapping = (Yaml.Mapping) lhs;
                    for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
                        if (entry.getValue() instanceof Yaml.Scalar &&
                                checkObjectEquality(((Yaml.Scalar) entry.getValue()).getValue(), rhs, operator)) {
                            return mapping;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public Object visitLiteralExpression(JsonPathParser.LiteralExpressionContext ctx) {
            String s = null;
            if (ctx.StringLiteral() != null) {
                s = ctx.StringLiteral().getText();
            } else if (!ctx.children.isEmpty()) {
                s = ctx.children.get(0).getText();
            }
            if (s != null && (s.startsWith("'") || s.startsWith("\""))) {
                return s.substring(1, s.length() - 1);
            }
            return "null".equals(s) ? null : s;
        }

        @Nullable
        private Object getBinaryExpressionResult(Object ctx) {
            if (ctx instanceof JsonPathParser.BinaryExpressionContext) {
                ctx = visitBinaryExpression((JsonPathParser.BinaryExpressionContext) ctx);
            } else if (ctx instanceof JsonPathParser.UnaryExpressionContext) {
                ctx = visitUnaryExpression((JsonPathParser.UnaryExpressionContext) ctx);
            } else if (ctx instanceof JsonPathParser.RegexExpressionContext) {
                ctx = visitRegexExpression((JsonPathParser.RegexExpressionContext) ctx);
            } else if (ctx instanceof JsonPathParser.LiteralExpressionContext) {
                ctx = visitLiteralExpression((JsonPathParser.LiteralExpressionContext) ctx);
            }
            return ctx;
        }

        @Nullable
        public Object getResultByKey(Object result, String key) {
            if (result instanceof Yaml.Mapping.Entry) {
                Yaml.Mapping.Entry member = (Yaml.Mapping.Entry) result;
                if (member.getValue() instanceof Yaml.Scalar) {
                    return member.getKey().getValue().equals(key) ? member : null;
                }
            } else if (result instanceof List) {
                for (Object o : ((List<Object>) result)) {
                    Object r = getResultByKey(o, key);
                    if (r != null) {
                        return r;
                    }
                }
            }
            return null;
        }

        private Object getResultFromList(Object results) {
            if (results instanceof List) {
                List<Object> matches = (List<Object>) results;
                if (matches.isEmpty()) {
                    return null;
                } else if (matches.size() == 1) {
                    return matches.get(0);
                }
            }
            return results;
        }

        // Extract the value from a Json object.
        @Nullable
        private Object getValue(Object result) {
            if (result instanceof Yaml.Mapping.Entry) {
                return getValue(((Yaml.Mapping.Entry) result).getValue());
            } else if (result instanceof Yaml.Mapping) {
                return ((Yaml.Mapping) result).getEntries();
            } else if (result instanceof List) {
                return ((List<Object>) result).stream()
                        .map(this::getValue)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            } else if (result instanceof Yaml.Sequence) {
                return ((Yaml.Sequence) result).getEntries();
            } else if (result instanceof Yaml.Scalar) {
                return ((Yaml.Scalar) result).getValue();
            } else if (result instanceof String) {
                return result;
            }

            return null;
        }

        private boolean checkObjectEquality(Object lhs, Object rhs, String operator) {
            if (lhs == null || rhs == null) {
                return false;
            }

            BiPredicate<Object, Object> predicate = (lh, rh) -> {
                if ("==".equals(operator)) {
                    return Objects.equals(lh, rh);
                } else if ("!=".equals(operator)) {
                    return !Objects.equals(lh, rh);
                }
                return false;
            };

            return predicate.test(lhs, rhs);
        }

        private static String unquoteStringLiteral(String literal) {
            if (literal != null && (literal.startsWith("'") || literal.startsWith("\""))) {
                return literal.substring(1, literal.length() - 1);
            }
            return "null".equals(literal) ? null : literal;
        }
    }
}
