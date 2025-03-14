package com.alibaba.fastjson2.schema;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONType;
import com.alibaba.fastjson2.reader.FieldReader;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.reader.ObjectReaderAdapter;
import com.alibaba.fastjson2.reader.ObjectReaderBean;
import com.alibaba.fastjson2.writer.ObjectWriter;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

@JSONType(serializer = JSONSchema.JSONSchemaWriter.class)
public abstract class JSONSchema {
    static final Map<String, JSONSchema> CACHE = new ConcurrentHashMap<>();

    final String title;
    final String description;

    static final JSONReader.Context CONTEXT = JSONFactory.createReadContext();

    JSONSchema(JSONObject input) {
        this.title = input.getString("title");
        this.description = input.getString("description");
    }

    JSONSchema(String title, String description) {
        this.title = title;
        this.description = description;
    }

    void addResolveTask(UnresolvedReference.ResolveTask task){
    }

    public static JSONSchema of(JSONObject input, Class objectClass) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        if (objectClass == null || objectClass == Object.class) {
            return of(input);
        }

        if (objectClass == byte.class
                || objectClass == short.class
                || objectClass == int.class
                || objectClass == long.class
                || objectClass == Byte.class
                || objectClass == Short.class
                || objectClass == Integer.class
                || objectClass == Long.class
                || objectClass == BigInteger.class
                || objectClass == AtomicInteger.class
                || objectClass == AtomicLong.class
        ) {
            if (input.containsKey("AnyOf") || input.containsKey("anyOf")) {
                return anyOf(input, objectClass);
            }

            if (input.containsKey("oneOf")) {
                return oneOf(input, objectClass);
            }

            if (input.containsKey("not")) {
                return ofNot(input, objectClass);
            }

            return new IntegerSchema(input);
        }

        if (objectClass == BigDecimal.class
                || objectClass == float.class
                || objectClass == double.class
                || objectClass == Float.class
                || objectClass == Double.class
                || objectClass == Number.class
        ) {
            if (input.containsKey("AnyOf") || input.containsKey("anyOf")) {
                return anyOf(input, objectClass);
            }

            if (input.containsKey("oneOf")) {
                return oneOf(input, objectClass);
            }

            if (input.containsKey("not")) {
                return ofNot(input, objectClass);
            }

            return new NumberSchema(input);
        }

        if (objectClass == boolean.class
                || objectClass == Boolean.class) {
            return new BooleanSchema(input);
        }

        if (objectClass == String.class) {
            return new StringSchema(input);
        }

        if (Collection.class.isAssignableFrom(objectClass)) {
            return new ArraySchema(input, null);
        }

        if (objectClass.isArray()) {
            return new ArraySchema(input, null);
        }

        return new ObjectSchema(input, null);
    }

    static Not ofNot(JSONObject input, Class objectClass) {
        Object not = input.get("not");
        if (not instanceof Boolean) {
            return new Not(null, null, (Boolean) not);
        }

        JSONObject object = (JSONObject) not;

        if (object == null || object.isEmpty()) {
            return new Not(null, new Type[]{Type.Any}, null);
        }

        if (object.size() == 1) {
            Object type = object.get("type");
            if (type instanceof JSONArray) {
                JSONArray array = (JSONArray) type;
                Type[] types = new Type[array.size()];
                for (int i = 0; i < array.size(); i++) {
                    types[i] = array.getObject(i, Type.class);
                }
                return new Not(null, types, null);
            }
        }

        JSONSchema schema = of(object, objectClass);
        return new Not(schema, null, null);
    }

    public static JSONSchema parseSchema(String schema) {
        if ("true".equals(schema)) {
            return Any.INSTANCE;
        }

        if ("false".equals(schema)) {
            return Any.NOT_ANY;
        }

        try (JSONReader reader = JSONReader.of(schema)) {
            ObjectReader<?> objectReader = reader.getObjectReader(Object.class);
            JSONObject object = (JSONObject) objectReader.readObject(reader, null, null, 0);
            return of(object);
        }
    }

    @JSONCreator
    public static JSONSchema of(JSONObject input) {
        return of(input, (JSONSchema) null);
    }

    public static JSONSchema of(java.lang.reflect.Type type) {
        return of(type, null);
    }

    public static JSONSchema ofValue(Object value) {
        return ofValue(value, null);
    }

    static JSONSchema ofValue(Object value, JSONSchema root) {
        if (value == null) {
            return null;
        }

        if (value instanceof Collection) {
            Collection collection = (Collection) value;
            if (collection.isEmpty()) {
                return new ArraySchema(JSONObject.of("type", "array"), root);
            }

            Object firstItem = null;
            Class firstItemClass = null;
            boolean sameClass = true;
            for (Object item : collection) {
                if (item != null) {
                    if (firstItem == null) {
                        firstItem = item;
                    }

                    if (firstItemClass == null) {
                        firstItemClass = item.getClass();
                    } else if (firstItemClass != item.getClass()) {
                        sameClass = false;
                    }
                }
            }

            if (sameClass) {
                JSONSchema itemSchema;
                if (Map.class.isAssignableFrom(firstItemClass)) {
                    itemSchema = ofValue(firstItem, root);
                } else {
                    itemSchema = of(firstItemClass, root);
                }
                ArraySchema schema = new ArraySchema(JSONObject.of("type", "array"), root);
                schema.itemSchema = itemSchema;
                return schema;
            }
        }

        if (value instanceof Map) {
            JSONObject object = JSONObject.of("type", "object");
            ObjectSchema schema = new ObjectSchema(object, root);

            Map map = (Map) value;
            for (Iterator it = map.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry entry = (Map.Entry) it.next();
                Object entryKey = entry.getKey();
                Object entryValue = entry.getValue();

                if (entryKey instanceof String) {
                    JSONSchema valueSchema;
                    if (entryValue == null) {
                        valueSchema = new StringSchema(JSONObject.of());
                    } else {
                        valueSchema = ofValue(entryValue, root == null ? schema : root);
                    }
                    schema.properties.put((String) entryKey, valueSchema);
                }
            }

            return schema;
        }

        return of(value.getClass(), root);
    }

    static JSONSchema of(java.lang.reflect.Type type, JSONSchema root) {
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;

            java.lang.reflect.Type rawType = paramType.getRawType();
            java.lang.reflect.Type[] arguments = paramType.getActualTypeArguments();

            if (rawType instanceof Class && Collection.class.isAssignableFrom((Class) rawType)) {
                JSONObject object = JSONObject.of("type", "array");
                ArraySchema arraySchema = new ArraySchema(object, root);
                if (arguments.length == 1) {
                    arraySchema.itemSchema = of(arguments[0], root == null ? arraySchema : root);
                }

                return arraySchema;
            }

            if (rawType instanceof Class
                    && Map.class.isAssignableFrom((Class) rawType)
            ) {
                JSONObject object = JSONObject.of("type", "object");
                return new ObjectSchema(object, root);
            }
        }

        if (type instanceof GenericArrayType) {
            GenericArrayType arrayType = (GenericArrayType) type;
            java.lang.reflect.Type componentType = arrayType.getGenericComponentType();
            JSONObject object = JSONObject.of("type", "array");
            ArraySchema arraySchema = new ArraySchema(object, root);
            arraySchema.itemSchema = of(componentType, root == null ? arraySchema : root);
            return arraySchema;
        }

        if (type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == BigInteger.class
                || type == AtomicInteger.class
                || type == AtomicLong.class
        ) {
            return new IntegerSchema(JSONObject.of("type", "integer"));
        }

        if (type == float.class
                || type == double.class
                || type == Float.class
                || type == Double.class
                || type == BigDecimal.class
        ) {
            return new NumberSchema(JSONObject.of("type", "number"));
        }

        if (type == boolean.class || type == Boolean.class || type == AtomicBoolean.class) {
            return new BooleanSchema(JSONObject.of("type", "boolean"));
        }

        if (type == String.class) {
            return new StringSchema(JSONObject.of("type", "string"));
        }

        if (type instanceof Class) {
            Class schemaClass = (Class) type;
            if (Enum.class.isAssignableFrom(schemaClass)) {
                Object[] enums = schemaClass.getEnumConstants();
                String[] names = new String[enums.length];
                for (int i = 0; i < enums.length; i++) {
                    names[i] = ((Enum) enums[i]).name();
                }
                return new StringSchema(JSONObject.of("type", "string", "enum", names));
            }

            if (schemaClass.isArray()) {
                Class componentType = schemaClass.getComponentType();
                JSONObject object = JSONObject.of("type", "array");
                ArraySchema arraySchema = new ArraySchema(object, root);
                arraySchema.itemSchema = of(componentType, root == null ? arraySchema : root);
                return arraySchema;
            }

            if (Map.class.isAssignableFrom(schemaClass)) {
                return new ObjectSchema(JSONObject.of("type", "object"), root);
            }

            if (Collection.class.isAssignableFrom(schemaClass)) {
                return new ArraySchema(JSONObject.of("type", "array"), root);
            }
        }

        ObjectReader reader = JSONFactory.getDefaultObjectReaderProvider().getObjectReader(type);

        if (reader instanceof ObjectReaderBean) {
            ObjectReaderAdapter adapter = (ObjectReaderAdapter) reader;

            JSONArray required = new JSONArray();
            adapter.apply((Consumer<FieldReader>) e -> {
                if (e.fieldClass.isPrimitive()) {
                    required.add(e.fieldName);
                }
            });

            JSONObject object = JSONObject.of("type", "object", "required", required);
            ObjectSchema schema = new ObjectSchema(object);
            adapter.apply((Consumer<FieldReader>) e -> {
                schema.properties.put(
                        e.fieldName,
                        of(e.fieldType, root == null ? schema : root)
                );
            });

            return schema;
        }

        throw new JSONException("TODO : " + type);
    }

    @JSONCreator
    public static JSONSchema of(JSONObject input, JSONSchema parent) {
        if (input.size() == 1 && input.isArray("type")) {
            JSONArray types = input.getJSONArray("type");
            JSONSchema[] items = new JSONSchema[types.size()];
            for (int i = 0; i < types.size(); i++) {
                items[i] = JSONSchema.of(JSONObject.of("type", types.get(i)));
            }
            return new AnyOf(items);
        }

        Type type = Type.of(
                input.getString("type")
        );

        if (type == null) {
            Object[] enums = input.getObject("enum", Object[].class);
            if (enums != null) {
                boolean nonString = false;
                for (Object anEnum : enums) {
                    if (!(anEnum instanceof String)) {
                        nonString = true;
                        break;
                    }
                }
                if (!nonString) {
                    return new StringSchema(input);
                }

                return new EnumSchema(enums);
            }

            Object constValue = input.get("const");
            if (constValue instanceof String) {
                return new StringSchema(input);
            } else if (constValue instanceof Integer || constValue instanceof Long) {
                return new IntegerSchema(input);
            }

            if (input.size() == 1) {
                String ref = input.getString("$ref");
                if (ref != null && !ref.isEmpty()) {
                    if ("http://json-schema.org/draft-04/schema#".equals(ref)) {
                        JSONSchema schema = CACHE.get(ref);
                        if (schema == null) {
                            URL draf4Resource = JSONSchema.class.getClassLoader().getResource("schema/draft-04.json");
                            schema = JSONSchema.of(
                                    JSON.parseObject(draf4Resource),
                                    (JSONSchema) null
                            );
                            JSONSchema origin = CACHE.putIfAbsent(ref, schema);
                            if (origin != null) {
                                schema = origin;
                            }
                        }
                        return schema;
                    }

                    if ("#".equals(ref)) {
                        return parent;
                    }

                    Map<String, JSONSchema> definitions = null, defs = null, properties = null;
                    if (parent instanceof ObjectSchema) {
                        ObjectSchema objectSchema = (ObjectSchema) parent;

                        definitions = objectSchema.definitions;
                        defs = objectSchema.defs;
                        properties = objectSchema.properties;
                    } else if (parent instanceof ArraySchema) {
                        definitions = ((ArraySchema) parent).definitions;
                        defs = ((ArraySchema) parent).defs;
                    }

                    if (definitions != null) {
                        if (ref.startsWith("#/definitions/")) {
                            final int PREFIX_LEN = 14; // "#/definitions/".length();
                            String refName = ref.substring(PREFIX_LEN);
                            return definitions.get(refName);
                        }
                    }

                    if (defs != null) {
                        if (ref.startsWith("#/$defs/")) {
                            final int PREFIX_LEN = 8; // "#/$defs/".length();
                            String refName = ref.substring(PREFIX_LEN);
                            refName = URLDecoder.decode(refName);
                            JSONSchema refSchema = defs.get(refName);
                            if (refSchema == null) {
                                refSchema = new UnresolvedReference(refName);
                            }
                            return refSchema;
                        }
                    }

                    if (properties != null) {
                        if (ref.startsWith("#/properties/")) {
                            final int PREFIX_LEN = 13; // "#/properties/".length();
                            String refName = ref.substring(PREFIX_LEN);
                            return properties.get(refName);
                        }
                    }

                    if (ref.startsWith("#/prefixItems/") && parent instanceof ArraySchema) {
                        final int PREFIX_LEN = 14; // "#/properties/".length();
                        int index = Integer.parseInt(ref.substring(PREFIX_LEN));
                        return ((ArraySchema) parent).prefixItems[index];
                    }
                }

                Object exclusiveMaximum = input.get("exclusiveMaximum");
                Object exclusiveMinimum = input.get("exclusiveMinimum");
                if (exclusiveMaximum instanceof Integer
                        || exclusiveMinimum instanceof Integer
                        || exclusiveMaximum instanceof Long
                        || exclusiveMinimum instanceof Long) {
                    return new IntegerSchema(input);
                }

                if (exclusiveMaximum instanceof Number || exclusiveMinimum instanceof Number) {
                    return new NumberSchema(input);
                }
            }

            if (input.containsKey("properties")
                    || input.containsKey("dependentSchemas")
                    || input.containsKey("if")
                    || input.containsKey("required")
                    || input.containsKey("patternProperties")
                    || input.containsKey("additionalProperties")
                    || input.containsKey("minProperties")
                    || input.containsKey("maxProperties")
                    || input.containsKey("propertyNames")
                    || input.containsKey("$ref")
            ) {
                return new ObjectSchema(input, parent);
            }

            if (input.containsKey("maxItems")
                    || input.containsKey("minItems")
                    || input.containsKey("additionalItems")
                    || input.containsKey("items")
                    || input.containsKey("prefixItems")
                    || input.containsKey("uniqueItems")
                    || input.containsKey("maxContains")
                    || input.containsKey("minContains")
            ) {
                return new ArraySchema(input, parent);
            }

            if (input.containsKey("pattern")
                    || input.containsKey("format")
                    || input.containsKey("minLength")
                    || input.containsKey("maxLength")
            ) {
                return new StringSchema(input);
            }

            boolean allOf = input.containsKey("allOf");
            boolean anyOf = input.containsKey("anyOf");
            boolean oneOf = input.containsKey("oneOf");

            if (allOf || anyOf || oneOf) {
                int count = (allOf ? 1 : 0) + (anyOf ? 1 : 0) + (oneOf ? 1 : 0);
                if (count == 1) {
                    if (allOf) {
                        return new AllOf(input, parent);
                    }

                    if (anyOf) {
                        return new AnyOf(input, parent);
                    }

                    return new OneOf(input, parent);
                }
                JSONSchema[] items = new JSONSchema[count];
                int index = 0;
                if (allOf) {
                    items[index++] = new AllOf(input, parent);
                }
                if (anyOf) {
                    items[index++] = new AnyOf(input, parent);
                }
                if (oneOf) {
                    items[index++] = new OneOf(input, parent);
                }
                return new AllOf(items);
            }

            if (input.containsKey("not")) {
                return ofNot(input, null);
            }

            if (input.get("maximum") instanceof Number
                    || input.get("minimum") instanceof Number
                    || input.containsKey("multipleOf")
            ) {
                return new NumberSchema(input);
            }

            if (input.isEmpty()) {
                return Any.INSTANCE;
            }

            if (input.size() == 1) {
                Object propertyType = input.get("type");
                if (propertyType instanceof JSONArray) {
                    JSONArray array = (JSONArray) propertyType;
                    JSONSchema[] typeSchemas = new JSONSchema[array.size()];
                    for (int i = 0; i < array.size(); i++) {
                        Type itemType = Type.of(array.getString(i));
                        switch (itemType) {
                            case String:
                                typeSchemas[i] = new StringSchema(JSONObject.of("type", "string"));
                                break;
                            case Integer:
                                typeSchemas[i] = new IntegerSchema(JSONObject.of("type", "integer"));
                                break;
                            case Number:
                                typeSchemas[i] = new NumberSchema(JSONObject.of("type", "number"));
                                break;
                            case Boolean:
                                typeSchemas[i] = new BooleanSchema(JSONObject.of("type", "boolean"));
                                break;
                            case Null:
                                typeSchemas[i] = new NullSchema(JSONObject.of("type", "null"));
                                break;
                            case Object:
                                typeSchemas[i] = new ObjectSchema(JSONObject.of("type", "object"));
                                break;
                            case Array:
                                typeSchemas[i] = new ArraySchema(JSONObject.of("type", "array"), null);
                                break;
                            default:
                                throw new JSONSchemaValidException("not support type : " + itemType);
                        }
                    }
                    return new AnyOf(typeSchemas);
                }
            }

            if (input.getString("type") == null) {
                throw new JSONSchemaValidException("type required");
            } else {
                throw new JSONSchemaValidException("not support type : " + input.getString("type"));
            }
        }

        switch (type) {
            case String:
                return new StringSchema(input);
            case Integer:
                return new IntegerSchema(input);
            case Number:
                return new NumberSchema(input);
            case Boolean:
                return new BooleanSchema(input);
            case Null:
                return new NullSchema(input);
            case Object:
                return new ObjectSchema(input, parent);
            case Array:
                return new ArraySchema(input, parent);
            default:
                throw new JSONSchemaValidException("not support type : " + type);
        }
    }

    static AnyOf anyOf(JSONObject input, Class type) {
        JSONArray array = input.getJSONArray("anyOf");
        return anyOf(array, type);
    }

    static JSONSchema[] makeSchemaItems(JSONArray array, Class type) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        JSONSchema[] items = new JSONSchema[array.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = JSONSchema.of(array.getJSONObject(i), type);
        }
        return items;
    }

    static AnyOf anyOf(JSONArray array, Class type) {
        JSONSchema[] items = makeSchemaItems(array, type);
        return items == null ? null : new AnyOf(items);
    }

    static AllOf allOf(JSONObject input, Class type) {
        JSONArray array = input.getJSONArray("allOf");
        JSONSchema[] items = makeSchemaItems(array, type);
        return items == null ? null : new AllOf(items);
    }

    static OneOf oneOf(JSONObject input, Class type) {
        JSONArray array = input.getJSONArray("oneOf");
        return oneOf(array, type);
    }

    static OneOf oneOf(JSONArray array, Class type) {
        JSONSchema[] items = makeSchemaItems(array, type);
        return items == null ? null : new OneOf(items);
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public abstract Type getType();

    public abstract ValidateResult validate(Object value);

    public boolean isValid(Object value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(long value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(double value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(Double value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(float value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(Float value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(Integer value) {
        return validate(value)
                .isSuccess();
    }

    public boolean isValid(Long value) {
        return validate(value)
                .isSuccess();
    }

    public ValidateResult validate(long value) {
        return validate((Object) value);
    }

    public ValidateResult validate(double value) {
        return validate((Object) value);
    }

    public ValidateResult validate(Float value) {
        return validate((Object) value);
    }

    public ValidateResult validate(Double value) {
        return validate((Object) value);
    }

    public ValidateResult validate(Integer value) {
        return validate((Object) value);
    }

    public ValidateResult validate(Long value) {
        return validate((Object) value);
    }

    public void assertValidate(Object value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(Integer value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(Long value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(Double value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(Float value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(long value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    public void assertValidate(double value) {
        ValidateResult result = validate(value);
        if (result.isSuccess()) {
            return;
        }
        throw new JSONSchemaValidException(result.getMessage());
    }

    @Override
    public String toString() {
        return toJSONObject()
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JSONSchema that = (JSONSchema) o;
        JSONObject thisObj = this.toJSONObject();
        JSONObject thatObj = that.toJSONObject();
        return thisObj.equals(thatObj);
    }

    @Override
    public int hashCode() {
        return toJSONObject().hashCode();
    }

    public JSONObject toJSONObject() {
        return new JSONObject();
    }

    public enum Type {
        Null,
        Boolean,
        Object,
        Array,
        Number,
        String,

        // extended type
        Integer,
        Enum,
        Const,
        OneOf,
        AllOf,
        AnyOf,
        Any,
        UnresolvedReference;

        public static Type of(String typeStr) {
            if (typeStr == null) {
                return null;
            }

            switch (typeStr) {
                case "Null":
                case "null":
                    return Type.Null;
                case "String":
                case "string":
                    return Type.String;
                case "Integer":
                case "integer":
                    return Type.Integer;
                case "Number":
                case "number":
                    return Type.Number;
                case "Boolean":
                case "boolean":
                    return Type.Boolean;
                case "Object":
                case "object":
                    return Type.Object;
                case "Array":
                case "array":
                    return Type.Array;
                default:
                    return null;
            }
        }
    }

    static final ValidateResult SUCCESS = new ValidateResult(true, "success");
    static final ValidateResult FAIL_INPUT_NULL = new ValidateResult(false, "input null");
    static final ValidateResult FAIL_INPUT_NOT_ENCODED = new ValidateResult(false, "input not encoded string");
    static final ValidateResult FAIL_ANY_OF = new ValidateResult(false, "anyOf fail");
    static final ValidateResult FAIL_ONE_OF = new ValidateResult(false, "oneOf fail");
    static final ValidateResult FAIL_NOT = new ValidateResult(false, "not fail");
    static final ValidateResult FAIL_TYPE_NOT_MATCH = new ValidateResult(false, "type not match");
    static final ValidateResult FAIL_PROPERTY_NAME = new ValidateResult(false, "propertyName not match");

    static final ValidateResult CONTAINS_NOT_MATCH = new ValidateResult(false, "contains not match");
    static final ValidateResult UNIQUE_ITEMS_NOT_MATCH = new ValidateResult(false, "uniqueItems not match");
    static final ValidateResult REQUIRED_NOT_MATCH = new ValidateResult(false, "required");

    static class JSONSchemaWriter
            implements ObjectWriter {
        @Override
        public void write(JSONWriter jsonWriter,
                          Object object,
                          Object fieldName,
                          java.lang.reflect.Type fieldType,
                          long features) {
            JSONObject jsonObject = ((JSONSchema) object).toJSONObject();
            jsonWriter.write(jsonObject);
        }
    }

    public void accept(Predicate<JSONSchema> v) {
        v.test(this);
    }

    static JSONObject injectIfPresent(JSONObject object, AllOf allOf, AnyOf anyOf, OneOf oneOf) {
        if (allOf != null) {
            object.put("allOf", allOf);
        }

        if (anyOf != null) {
            object.put("anyOf", anyOf);
        }

        if (oneOf != null) {
            object.put("oneOf", oneOf);
        }
        return object;
    }
}
