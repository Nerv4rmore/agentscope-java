/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Function tool definition for the OpenAI Responses API.
 *
 * <p>Unlike Chat Completions' nested {@code {type:"function", function:{...}}} structure,
 * the Responses API flattens the function fields directly onto the tool object.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "type": "function",
 *   "name": "get_current_weather",
 *   "description": "Get the current weather in a given location",
 *   "parameters": {
 *     "type": "object",
 *     "properties": { "location": {"type": "string"} },
 *     "required": ["location"]
 *   },
 *   "strict": true
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesFunctionTool {

    /** Tool type, always "function". */
    @JsonProperty("type")
    private String type = "function";

    /** The name of the function to call. */
    @JsonProperty("name")
    private String name;

    /** A description of the function, used by the model to decide whether to call it. */
    @JsonProperty("description")
    private String description;

    /** A JSON schema object describing the parameters of the function. */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    /** Whether strict parameter validation is enforced. */
    @JsonProperty("strict")
    private Boolean strict;

    public ResponsesFunctionTool() {}

    /**
     * Creates a function tool with the given name, description, and parameters.
     *
     * @param name        the function name
     * @param description the function description
     * @param parameters  the JSON schema parameters
     */
    public ResponsesFunctionTool(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    /**
     * Factory method to create a function tool.
     *
     * @param name        the function name
     * @param description the function description
     * @param parameters  the JSON schema parameters
     * @return a new ResponsesFunctionTool
     */
    public static ResponsesFunctionTool function(
            String name, String description, Map<String, Object> parameters) {
        return new ResponsesFunctionTool(name, description, parameters);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }
}
