/*
 * Copyright 2016 flipkart.com zjsonpatch.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.zjsonpatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.collections4.ListUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * User: gopi.vishwakarma
 * Date: 30/07/14
 */

public final class JsonDiff {

    private JsonDiff() {

    }

    public static JsonNode asJson(final JsonNode source, final JsonNode target) {
        return asJson(source, target, DiffFlags.defaults());
    }

    public static JsonNode asJson(final JsonNode source, final JsonNode target, EnumSet<DiffFlags> flags) {
        //创建存储最终比较结果的集合
        final List<Diff> diffs = new ArrayList<Diff>();
        List<Object> path = new ArrayList<Object>(0);

        // generating diffs in the order of their occurrence
        //按照资源的顺序构建不同的内容
        generateDiffs(diffs, path, source, target);

        if (!flags.contains(DiffFlags.OMIT_MOVE_OPERATION)) {

            // Merging remove & add to move operation

            compactDiffs(diffs);
        }

        if (!flags.contains(DiffFlags.OMIT_COPY_OPERATION)) {

            // Introduce copy operation

            introduceCopyOperation(source, target, diffs);
        }

        return getJsonNodes(diffs, flags);
    }

    private static List<Object> getMatchingValuePath(Map<JsonNode, List<Object>> unchangedValues, JsonNode value) {
        return unchangedValues.get(value);
    }

    private static void introduceCopyOperation(JsonNode source, JsonNode target, List<Diff> diffs) {
        Map<JsonNode, List<Object>> unchangedValues = getUnchangedPart(source, target);
        for (int i = 0; i < diffs.size(); i++) {
            Diff diff = diffs.get(i);
            if (Operation.ADD == diff.getOperation()) {
                List<Object> matchingValuePath = getMatchingValuePath(unchangedValues, diff.getValue());
                if (matchingValuePath != null && isAllowed(matchingValuePath, diff.getPath())) {
                    diffs.set(i, new Diff(Operation.COPY, matchingValuePath, diff.getPath()));
                }
            }
        }
    }

    private static boolean isNumber(String str) {
        int size = str.length();

        for (int i = 0; i < size; i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }

        return size > 0;
    }

    private static boolean isAllowed(List<Object> source, List<Object> destination) {
        boolean isSame = source.equals(destination);
        int i = 0;
        int j = 0;
        //Hack to fix broken COPY operation, need better handling here
        while (i < source.size() && j < destination.size()) {
            Object srcValue = source.get(i);
            Object dstValue = destination.get(j);
            String srcStr = srcValue.toString();
            String dstStr = dstValue.toString();
            if (isNumber(srcStr) && isNumber(dstStr)) {

                if (srcStr.compareTo(dstStr) > 0) {
                    return false;
                }
            }
            i++;
            j++;

        }
        return !isSame;
    }

    private static Map<JsonNode, List<Object>> getUnchangedPart(JsonNode source, JsonNode target) {
        Map<JsonNode, List<Object>> unchangedValues = new HashMap<JsonNode, List<Object>>();
        computeUnchangedValues(unchangedValues, new ArrayList<Object>(), source, target);
        return unchangedValues;
    }

    private static void computeUnchangedValues(Map<JsonNode, List<Object>> unchangedValues, List<Object> path, JsonNode source, JsonNode target) {
        if (source.equals(target)) {
            if (!unchangedValues.containsKey(target)) {
                unchangedValues.put(target, path);
            }
            return;
        }

        final NodeType firstType = NodeType.getNodeType(source);
        final NodeType secondType = NodeType.getNodeType(target);

        if (firstType == secondType) {
            switch (firstType) {
                case OBJECT:
                    computeObject(unchangedValues, path, source, target);
                    break;
                case ARRAY:
                    computeArray(unchangedValues, path, source, target);
                    break;
                default:
                    /* nothing */
            }
        }
    }

    private static void computeArray(Map<JsonNode, List<Object>> unchangedValues, List<Object> path, JsonNode source, JsonNode target) {
        final int size = Math.min(source.size(), target.size());

        for (int i = 0; i < size; i++) {
            List<Object> currPath = getPath(path, i);
            computeUnchangedValues(unchangedValues, currPath, source.get(i), target.get(i));
        }
    }

    private static void computeObject(Map<JsonNode, List<Object>> unchangedValues, List<Object> path, JsonNode source, JsonNode target) {
        final Iterator<String> firstFields = source.fieldNames();
        while (firstFields.hasNext()) {
            String name = firstFields.next();
            if (target.has(name)) {
                List<Object> currPath = getPath(path, name);
                computeUnchangedValues(unchangedValues, currPath, source.get(name), target.get(name));
            }
        }
    }

    /**
     * This method merge 2 diffs ( remove then add, or vice versa ) with same value into one Move operation,
     * all the core logic resides here only
     */
    private static void compactDiffs(List<Diff> diffs) {
        for (int i = 0; i < diffs.size(); i++) {
            Diff diff1 = diffs.get(i);

            // if not remove OR add, move to next diff
            if (!(Operation.REMOVE == diff1.getOperation() ||
                    Operation.ADD == diff1.getOperation())) {
                continue;
            }

            for (int j = i + 1; j < diffs.size(); j++) {
                Diff diff2 = diffs.get(j);
                if (!diff1.getValue().equals(diff2.getValue())) {
                    continue;
                }

                Diff moveDiff = null;
                if (Operation.REMOVE == diff1.getOperation() &&
                        Operation.ADD == diff2.getOperation()) {
                    computeRelativePath(diff2.getPath(), i + 1, j - 1, diffs);
                    moveDiff = new Diff(Operation.MOVE, diff1.getPath(), diff2.getPath());

                } else if (Operation.ADD == diff1.getOperation() &&
                        Operation.REMOVE == diff2.getOperation()) {
                    computeRelativePath(diff2.getPath(), i, j - 1, diffs); // diff1's add should also be considered
                    moveDiff = new Diff(Operation.MOVE, diff2.getPath(), diff1.getPath());
                }
                if (moveDiff != null) {
                    diffs.remove(j);
                    diffs.set(i, moveDiff);
                    break;
                }
            }
        }
    }

    //Note : only to be used for arrays
    //Finds the longest common Ancestor ending at Array
    private static void computeRelativePath(List<Object> path, int startIdx, int endIdx, List<Diff> diffs) {
        List<Integer> counters = new ArrayList<Integer>(path.size());

        resetCounters(counters, path.size());

        for (int i = startIdx; i <= endIdx; i++) {
            Diff diff = diffs.get(i);
            //Adjust relative path according to #ADD and #Remove
            if (Operation.ADD == diff.getOperation() || Operation.REMOVE == diff.getOperation()) {
                updatePath(path, diff, counters);
            }
        }
        updatePathWithCounters(counters, path);
    }

    private static void resetCounters(List<Integer> counters, int size) {
        for (int i = 0; i < size; i++) {
            counters.add(0);
        }
    }

    private static void updatePathWithCounters(List<Integer> counters, List<Object> path) {
        for (int i = 0; i < counters.size(); i++) {
            int value = counters.get(i);
            if (value != 0) {
                Integer currValue = Integer.parseInt(path.get(i).toString());
                path.set(i, String.valueOf(currValue + value));
            }
        }
    }

    private static void updatePath(List<Object> path, Diff pseudo, List<Integer> counters) {
        //find longest common prefix of both the paths

        if (pseudo.getPath().size() <= path.size()) {
            int idx = -1;
            for (int i = 0; i < pseudo.getPath().size() - 1; i++) {
                if (pseudo.getPath().get(i).equals(path.get(i))) {
                    idx = i;
                } else {
                    break;
                }
            }
            if (idx == pseudo.getPath().size() - 2) {
                if (pseudo.getPath().get(pseudo.getPath().size() - 1) instanceof Integer) {
                    updateCounters(pseudo, pseudo.getPath().size() - 1, counters);
                }
            }
        }
    }

    private static void updateCounters(Diff pseudo, int idx, List<Integer> counters) {
        if (Operation.ADD == pseudo.getOperation()) {
            counters.set(idx, counters.get(idx) - 1);
        } else {
            if (Operation.REMOVE == pseudo.getOperation()) {
                counters.set(idx, counters.get(idx) + 1);
            }
        }
    }

    private static ArrayNode getJsonNodes(List<Diff> diffs, EnumSet<DiffFlags> flags) {
        JsonNodeFactory FACTORY = JsonNodeFactory.instance;
        final ArrayNode patch = FACTORY.arrayNode();
        for (Diff diff : diffs) {
            ObjectNode jsonNode = getJsonNode(FACTORY, diff, flags);
            patch.add(jsonNode);
        }
        return patch;
    }

    private static ObjectNode getJsonNode(JsonNodeFactory FACTORY, Diff diff, EnumSet<DiffFlags> flags) {
        ObjectNode jsonNode = FACTORY.objectNode();
        jsonNode.put(Constants.OP, diff.getOperation().rfcName());

        switch (diff.getOperation()) {
            case MOVE:
            case COPY:
                jsonNode.put(Constants.FROM, PathUtils.getPathRepresentation(diff.getPath()));    // required {from} only in case of Move Operation
                jsonNode.put(Constants.PATH, PathUtils.getPathRepresentation(diff.getToPath()));  // destination Path
                break;

            case REMOVE:
                jsonNode.put(Constants.PATH, PathUtils.getPathRepresentation(diff.getPath()));
                if (!flags.contains(DiffFlags.OMIT_VALUE_ON_REMOVE))
                    jsonNode.set(Constants.VALUE, diff.getValue());
                break;

            case REPLACE:
                if (flags.contains(DiffFlags.ADD_ORIGINAL_VALUE_ON_REPLACE)) {
                    jsonNode.set(Constants.FROM_VALUE, diff.getSrcValue());
                }
            case ADD:
            case TEST:
                jsonNode.put(Constants.PATH, PathUtils.getPathRepresentation(diff.getPath()));
                jsonNode.set(Constants.VALUE, diff.getValue());
                break;

            default:
                // Safety net
                throw new IllegalArgumentException("Unknown operation specified:" + diff.getOperation());
        }

        return jsonNode;
    }

    private static void generateDiffs(List<Diff> diffs, List<Object> path, JsonNode source, JsonNode target) {
        if (!source.equals(target)) {
            final NodeType sourceType = NodeType.getNodeType(source);
            final NodeType targetType = NodeType.getNodeType(target);

            if (sourceType == NodeType.ARRAY && targetType == NodeType.ARRAY) {
                //both are arrays
                compareArray(diffs, path, source, target);
            } else if (sourceType == NodeType.OBJECT && targetType == NodeType.OBJECT) {
                //both are json
                compareObjects(diffs, path, source, target);
            } else {
                //can be replaced

                diffs.add(Diff.generateDiff(Operation.REPLACE, path, source, target));
            }
        }
    }

    private static void compareArray(List<Diff> diffs, List<Object> path, JsonNode source, JsonNode target) {
        List<JsonNode> lcs = getLCS(source, target);
        //source 指针
        int srcIdx = 0;
        //target 指针
        int targetIdx = 0;
        //lcs 指针
        int lcsIdx = 0;
        //source 尺寸
        int srcSize = source.size();
        //target 尺寸
        int targetSize = target.size();
        //lcs 尺寸
        int lcsSize = lcs.size();
        //数组里的对象坐标
        int pos = 0;
        while (lcsIdx < lcsSize) {
            JsonNode lcsNode = lcs.get(lcsIdx);
            JsonNode srcNode = source.get(srcIdx);
            JsonNode targetNode = target.get(targetIdx);

            // Both are same as lcs node, nothing to do here
            // lcs node 和 src target都相同 则所有指针移动 继续比较
            if (lcsNode.equals(srcNode) && lcsNode.equals(targetNode)) {
                srcIdx++;
                targetIdx++;
                lcsIdx++;
                pos++;
            } else {
                // src node is same as lcs, but not targetNode
                //lcs拿到的值和src node值相同 但是和target node值不同
                // 说明target比source多，所以source 需要add 当前targetNode
                if (lcsNode.equals(srcNode)) {
                    //addition
                    List<Object> currPath = getPath(path, pos);
                    //targetNode 需要add 这个path的 node
                    diffs.add(Diff.generateDiff(Operation.ADD, currPath, targetNode));
                    //数组里的对象坐标指针移动
                    pos++;
                    //target指针移动 比较target 下一个node
                    targetIdx++;

                    //targetNode node is same as lcs, but not src
                    //lcs拿到的值和target node值相同 但是和src node值不同
                    //删除操作 数组里的对象坐标指针不移动
                    //说明source 比 target多 所以需要source remove 当前srcNode
                } else if (lcsNode.equals(targetNode)) {
                    //removal,
                    List<Object> currPath = getPath(path, pos);
                    //srcNode 需要remove 这个path的 node
                    diffs.add(Diff.generateDiff(Operation.REMOVE, currPath, srcNode));
                    //source指针移动 比较source 下一个node
                    srcIdx++;
                } else {
                    List<Object> currPath = getPath(path, pos);
                    //both are unequal to lcs node
                    //如果srcNode 和 targetNode 和 lcsNode都不同，则继续递归比较
                    generateDiffs(diffs, currPath, srcNode, targetNode);
                    //指针移动 比较source,target 下一个node
                    srcIdx++;
                    targetIdx++;
                    //数组里的对象坐标指针移动
                    pos++;
                }
            }
        }

        //最大公共子串和source，target比较完成后，
        // 检查source 和 target node节点是否都遍历比较完成
        // 如果没有完成 则继续递归调用
        while ((srcIdx < srcSize) && (targetIdx < targetSize)) {
            JsonNode srcNode = source.get(srcIdx);
            JsonNode targetNode = target.get(targetIdx);
            List<Object> currPath = getPath(path, pos);
            generateDiffs(diffs, currPath, srcNode, targetNode);
            srcIdx++;
            targetIdx++;
            pos++;
        }

        //处理剩余的target node
        pos = addRemaining(diffs, path, target, pos, targetIdx, targetSize);
        //处理剩余的source node
        removeRemaining(diffs, path, pos, srcIdx, srcSize, source);
    }

    private static Integer removeRemaining(List<Diff> diffs, List<Object> path, int pos, int srcIdx, int srcSize, JsonNode source) {
        //比较完成之后  剩余的srcNode 需要remove 这个path的 node
        while (srcIdx < srcSize) {
            List<Object> currPath = getPath(path, pos);
            diffs.add(Diff.generateDiff(Operation.REMOVE, currPath, source.get(srcIdx)));
            srcIdx++;
        }
        return pos;
    }

    private static Integer addRemaining(List<Diff> diffs, List<Object> path, JsonNode target, int pos, int targetIdx, int targetSize) {
        //比较完成之后，剩余的targetNode 需要构建成 add 这个path的 node
        while (targetIdx < targetSize) {
            JsonNode jsonNode = target.get(targetIdx);
            List<Object> currPath = getPath(path, pos);
            diffs.add(Diff.generateDiff(Operation.ADD, currPath, jsonNode.deepCopy()));
            pos++;
            targetIdx++;
        }
        return pos;
    }

    private static void compareObjects(List<Diff> diffs, List<Object> path, JsonNode source, JsonNode target) {
        Iterator<String> keysFromSrc = source.fieldNames();
        while (keysFromSrc.hasNext()) {
            String key = keysFromSrc.next();
            if (!target.has(key)) {
                //remove case
                List<Object> currPath = getPath(path, key);
                diffs.add(Diff.generateDiff(Operation.REMOVE, currPath, source.get(key)));
                continue;
            }
            List<Object> currPath = getPath(path, key);
            generateDiffs(diffs, currPath, source.get(key), target.get(key));
        }
        Iterator<String> keysFromTarget = target.fieldNames();
        while (keysFromTarget.hasNext()) {
            String key = keysFromTarget.next();
            if (!source.has(key)) {
                //add case
                List<Object> currPath = getPath(path, key);
                diffs.add(Diff.generateDiff(Operation.ADD, currPath, target.get(key)));
            }
        }
    }

    private static List<Object> getPath(List<Object> path, Object key) {
        List<Object> toReturn = new ArrayList<Object>(path.size() + 1);
        toReturn.addAll(path);
        toReturn.add(key);
        return toReturn;
    }

    private static List<JsonNode> getLCS(final JsonNode first, final JsonNode second) {
        return ListUtils.longestCommonSubsequence(InternalUtils.toList((ArrayNode) first), InternalUtils.toList((ArrayNode) second));
    }
}
