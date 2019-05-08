/*
 * <<
 * Davinci
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * >>
 */

package edp.davinci.core.utils;

import com.alibaba.druid.util.StringUtils;
import com.sun.tools.javac.util.ListBuffer;
import edp.core.exception.ServerException;
import edp.core.utils.SqlUtils;
import edp.davinci.core.common.Constants;
import edp.davinci.core.enums.SqlOperatorEnum;
import edp.davinci.core.enums.SqlVariableTypeEnum;
import edp.davinci.core.enums.SqlVariableValueTypeEnum;
import edp.davinci.core.model.SqlEntity;
import edp.davinci.model.SqlVariable;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.stringtemplate.v4.ST;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edp.core.consts.Consts.*;
import static edp.davinci.core.common.Constants.*;

@Slf4j
public class SqlParseUtils {


    private static final char STStartChar = '{';

    private static final char STEndChar = '}';

    private static final String REG_SQL_STRUCT = "[{].*[}]";

    private static final String SELECT = "select";

    private static final String WITH = "with";

    private static final String QUERY_VAR_KEY = "query@var";

    private static final String TEAM_VAR_KEY = "team@var";

    private static final ExecutorService executorService = Executors.newFixedThreadPool(8);

    /**
     * 解析sql
     *
     * @param sqlStr
     * @return
     */
    public static SqlEntity parseSql(String sqlStr, String sqlTempDelimiter) throws ServerException {
        if (!StringUtils.isEmpty(sqlStr.trim())) {
            //过滤注释
            sqlStr = SqlUtils.filterAnnotate(sqlStr);

            //sql体
            String sqlStruct = null, queryParam = null;
            //Pattern.DOTALL+Pattern.MULTILINE : 在正则表达式中的'.'可以代替所有字符，包括换行符\n
            Pattern p = Pattern.compile(REG_SQL_STRUCT, Pattern.DOTALL + Pattern.MULTILINE);
            Matcher matcher = p.matcher(sqlStr);
            if (matcher.find()) {
                sqlStruct = matcher.group();
                queryParam = matcher.replaceAll("");
            } else {
                throw new ServerException("You have an error in your SQL syntax;");
            }

            //sql体
            if (!StringUtils.isEmpty(sqlStruct.trim())) {
                sqlStruct = sqlStruct.trim();

                if (sqlStruct.startsWith(String.valueOf(STStartChar))) {
                    sqlStruct = sqlStruct.substring(1);
                }
                if (sqlStruct.endsWith(String.valueOf(STEndChar))) {
                    sqlStruct = sqlStruct.substring(0, sqlStruct.length() - 1);
                }
                if (sqlStruct.endsWith(semicolon)) {
                    sqlStruct = sqlStruct.substring(0, sqlStruct.length() - 1);
                }
            }

            Map<String, String> queryParamMap = new HashMap<>();
            Map<String, List<String>> teamParamMap = new HashMap<>();
            //参数
            if (!StringUtils.isEmpty(queryParam)) {
                queryParam = queryParam.trim().replaceAll(newLineChar, semicolon).trim();
                queryParam = queryParam.replaceAll(semicolon + "{2,}", semicolon);
                if (queryParam.endsWith(semicolon)) {
                    queryParam = queryParam.substring(0, queryParam.length() - 1);
                }
                String[] split = queryParam.split(semicolon);
                if (null != split && split.length > 0) {
                    for (String param : split) {
                        param = param.trim();
                        if (param.startsWith(QUERY_VAR_KEY)) {
                            param = param.replaceAll(QUERY_VAR_KEY, "");
                            String[] paramArray = param.trim().split(String.valueOf(assignmentChar));
                            if (null != paramArray && paramArray.length > 0) {
                                String k = paramArray[0];
                                String v = paramArray.length > 1 ? param.replace(k + assignmentChar, "").trim() : null;
                                queryParamMap.put(k.trim().replace(String.valueOf(getSqlTempDelimiter(sqlTempDelimiter)), ""), v);
                            }
                        } else if (param.startsWith(TEAM_VAR_KEY)) {
                            param = param.replaceAll(TEAM_VAR_KEY, "").trim();
                            String[] paramArray = param.trim().split(String.valueOf(assignmentChar));
                            if (null != paramArray && paramArray.length > 0) {
                                String k = paramArray[0];
                                String v = paramArray.length > 1 ? param.replace(k + assignmentChar, "").trim() : null;
                                teamParamMap.put(k.trim(), Arrays.asList(v));
                            }
                        }
                    }
                }
            }

            if (StringUtils.isEmpty(sqlStruct)) {
                throw new ServerException("Invalid Query Sql");
            }

            sqlStruct = sqlStruct.replaceAll(newLineChar, space).trim();

            Map<String, Object> map = null;

            SqlEntity sqlEntity = new SqlEntity(sqlStruct, map, teamParamMap);
            return sqlEntity;
        }
        return null;
    }


    public static SqlEntity parseSql(String sqlStr, List<SqlVariable> variables, String sqlTempDelimiter) throws ServerException {
        if (StringUtils.isEmpty(sqlStr.trim())) {
            return null;
        }

        sqlStr = SqlUtils.filterAnnotate(sqlStr);
        sqlStr = sqlStr.replaceAll(newLineChar, space).trim();

        char delimiter = getSqlTempDelimiter(sqlTempDelimiter);

        Pattern p = Pattern.compile(getReg(REG_SQL_PLACEHOLDER, delimiter));
        Matcher matcher = p.matcher(sqlStr);

        if (!matcher.find()) {
            return new SqlEntity(sqlStr, null, null);
        }

        Map<String, Object> queryParamMap = new ConcurrentHashMap<>();
        Map<String, List<String>> authParamMap = new ConcurrentHashMap<>();

        //解析参数
        if (null != variables && variables.size() > 0) {
            try {
                CountDownLatch countDownLatch = new CountDownLatch(variables.size());
                variables.forEach(variable -> executorService.execute(() -> {
                    SqlVariableTypeEnum typeEnum = SqlVariableTypeEnum.typeOf(variable.getType());
                    if (null != typeEnum) {
                        switch (typeEnum) {
                            case QUERYVAR:
                                queryParamMap.put(variable.getName().trim(), SqlVariableValueTypeEnum.getValue(variable.getValueType(), variable.getDefaultValues()));
                                break;
                            case AUTHVARE:
                                authParamMap.put(String.join("", String.valueOf(delimiter), variable.getName().trim(), String.valueOf(delimiter)), getAuthVarValue(variable, null));
                                break;
                        }
                    }
                    countDownLatch.countDown();

                }));
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return new SqlEntity(sqlStr, queryParamMap, authParamMap);
    }


    public static List<String> getAuthVarValue(SqlVariable variable, String outDataUrl) {
        if (null == variable) {
            return null;
        }
        if (null == variable.getChannel()) {
            return SqlVariableValueTypeEnum.getValue(variable.getValueType(), variable.getDefaultValues());
        } else if (!StringUtils.isEmpty(outDataUrl)) {
            //TODO  获取外部接口数据

        }
        return null;
    }

    /**
     * 替换参数
     *
     * @param sql
     * @param queryParamMap
     * @param authParamMap
     * @param sqlTempDelimiter
     * @return
     */
    public static String replaceParams(String sql, Map<String, Object> queryParamMap, Map<String, List<String>> authParamMap, String sqlTempDelimiter) {
        if (StringUtils.isEmpty(sql)) {
            return null;
        }

        char delimiter = getSqlTempDelimiter(sqlTempDelimiter);

        //替换team@var
        Pattern p = Pattern.compile(getReg(REG_AUTHVAR, delimiter));
        Matcher matcher = p.matcher(sql);
        String parenthesesEndREG = "\\" + parenthesesEnd + "{2,}";

        Set<String> expSet = new HashSet<>();
        while (matcher.find()) {
            expSet.add(matcher.group().replaceAll(parenthesesEndREG, parenthesesEnd));
        }
        if (expSet.size() > 0) {
            Map<String, String> parsedMap = getParsedExpression(expSet, authParamMap, delimiter);
            for (String key : parsedMap.keySet()) {
                if (sql.indexOf(key) > -1) {
                    sql = sql.replace(key, parsedMap.get(key));
                }
            }
        }

        ST st = new ST(sql, delimiter, delimiter);
        //替换query@var
        if (null != queryParamMap && queryParamMap.size() > 0) {
            for (String key : queryParamMap.keySet()) {
                st.add(key, queryParamMap.get(key));
            }
        }
        sql = st.render();
        return sql;
    }


    public static List<String> getSqls(String sql, boolean isQuery) {
        sql = sql.trim();

        if (StringUtils.isEmpty(sql)) {
            return null;
        }

        if (sql.startsWith(semicolon)) {
            sql = sql.substring(1);
        }

        if (sql.endsWith(semicolon)) {
            sql = sql.substring(0, sql.length() - 1);
        }

        List<String> list = null;

        String[] split = sql.split(semicolon);
        if (null != split && split.length > 0) {
            list = new ArrayList<>();
            for (String sqlStr : split) {
                sqlStr = sqlStr.trim();
                boolean select = sqlStr.toLowerCase().startsWith(SELECT) || sqlStr.toLowerCase().startsWith(WITH);
                if (isQuery) {
                    if (select) {
                        list.add(sqlStr);
                    } else {
                        continue;
                    }
                } else {
                    if (!select) {
                        list.add(sqlStr);
                    } else {
                        continue;
                    }
                }
            }
        }
        return list;
    }


    private static Map<String, String> getParsedExpression(Set<String> expSet, Map<String, List<String>> authParamMap, char sqlTempDelimiter) {
        Iterator<String> iterator = expSet.iterator();
        Map<String, String> map = new HashMap<>();
        while (iterator.hasNext()) {
            String exp = iterator.next().trim();
            try {
                map.put(exp, getAuthVarExpression(exp, authParamMap, sqlTempDelimiter));
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
        }
        if (map.size() > 0) {
            return map;
        } else {
            return null;
        }
    }

    private static String getAuthVarExpression(String srcExpression, Map<String, List<String>> authParamMap, char sqlTempDelimiter) throws Exception {

        if (null == authParamMap) {
            return "1=1";
        }

        String originExpression = "";
        if (!StringUtils.isEmpty(srcExpression)) {
            srcExpression = srcExpression.trim();
            if (srcExpression.startsWith(parenthesesStart) && srcExpression.endsWith(parenthesesEnd)) {
                srcExpression = srcExpression.substring(1, srcExpression.length() - 1);
            }

            String sql = String.format(Constants.SELECT_EXEPRESSION, srcExpression);
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            Expression where = plainSelect.getWhere();

            ListBuffer<Map<SqlOperatorEnum, List<String>>> listBuffer = new ListBuffer<>();
            where.accept(SqlOperatorEnum.getVisitor(listBuffer));
            Map<SqlOperatorEnum, List<String>> operatorMap = listBuffer.toList().head;

            for (SqlOperatorEnum sqlOperator : operatorMap.keySet()) {
                List<String> expList = operatorMap.get(sqlOperator);
                if (null != expList && expList.size() > 0) {
                    String left = operatorMap.get(sqlOperator).get(0);
                    String right = operatorMap.get(sqlOperator).get(expList.size() - 1);
                    if (right.startsWith(parenthesesStart) && right.endsWith(parenthesesEnd)) {
                        right = right.substring(1, right.length() - 1);
                    }
                    if (authParamMap.containsKey(right)) {
                        StringBuilder expBuilder = new StringBuilder();
                        List<String> list = authParamMap.get(right);
                        if (null != list && list.size() > 0) {
                            if (list.size() == 1) {
                                if (!StringUtils.isEmpty(list.get(0))) {
                                    switch (sqlOperator) {
                                        case IN:
                                            expBuilder
                                                    .append(left).append(space)
                                                    .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                    .append(list.stream().collect(Collectors.joining(",", "(", ")")));
                                            break;
                                        default:
                                            if (list.get(0).split(",").length > 1) {
                                                expBuilder
                                                        .append(left).append(space)
                                                        .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                        .append(list.stream().collect(Collectors.joining(",", "(", ")")));
                                            } else {
                                                expBuilder
                                                        .append(left).append(space)
                                                        .append(sqlOperator.getValue()).append(space).append(list.get(0));
                                            }
                                            break;
                                    }
                                } else {
                                    return "1=1";
                                }
                            } else {
                                switch (sqlOperator) {
                                    case IN:
                                    case EQUALSTO:
                                        expBuilder
                                                .append(left).append(space)
                                                .append(SqlOperatorEnum.IN.getValue()).append(space)
                                                .append(list.stream().collect(Collectors.joining(",", "(", ")")));
                                        break;

                                    case NOTEQUALSTO:
                                        expBuilder
                                                .append(left).append(space)
                                                .append(SqlOperatorEnum.NoTIN.getValue()).append(space)
                                                .append(list.stream().collect(Collectors.joining(",", "(", ")")));
                                        break;

                                    case BETWEEN:
                                    case GREATERTHAN:
                                    case GREATERTHANEQUALS:
                                    case MINORTHAN:
                                    case MINORTHANEQUALS:
                                        expBuilder.append(list.stream()
                                                .map(x -> space + left + space + SqlOperatorEnum.BETWEEN.getValue() + space + x + space)
                                                .collect(Collectors.joining("or", "(", ")")));
                                        break;

                                    default:
                                        expBuilder.append(originExpression);
                                        break;
                                }
                            }
                        }
                        return expBuilder.toString();
                    } else {
                        return "1=0";
                    }
                }
            }
        }
        return originExpression;
    }
}
