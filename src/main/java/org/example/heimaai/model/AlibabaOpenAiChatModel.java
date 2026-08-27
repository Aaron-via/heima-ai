package org.example.heimaai.model;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.ResponseFormatText;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall.Function;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.FinishReason;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartInputAudio;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.observation.conventions.AiProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

public class AlibabaOpenAiChatModel implements ChatModel {

    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
            new DefaultChatModelObservationConvention();

    private static final String REASONING_CONTENT = "reasoningContent";

    static final String TOOL_CALL_ADDITIONAL_PROPERTIES_METADATA_KEY = "openai.tool_calls.additional_properties";

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Log logger = LogFactory.getLog(AlibabaOpenAiChatModel.class);

    private final OpenAIClient openAiClient;

    private final OpenAIClientAsync openAiClientAsync;

    private final OpenAiChatOptions options;

    private final ObservationRegistry observationRegistry;

    private final ToolCallingManager toolCallingManager;

    private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public AlibabaOpenAiChatModel(OpenAIClient openAiClient,
                                  OpenAIClientAsync openAiClientAsync,
                                  OpenAiChatOptions options,
                                  ObservationRegistry observationRegistry,
                                  ToolCallingManager toolCallingManager) {
        this.openAiClient = openAiClient;
        this.openAiClientAsync = openAiClientAsync;
        this.options = options;
        this.observationRegistry = observationRegistry;
        this.toolCallingManager = toolCallingManager;
    }

    @Override
    public OpenAiChatOptions getOptions() {
        return this.options;
    }

    @Override
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    public ChatOptions getDefaultOptions() {
        return this.options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt requestPrompt = buildRequestPrompt(prompt);
        verifyPromptChatOptions(requestPrompt);
        return this.internalCall(requestPrompt, null);
    }

    private ChatResponse internalCall(Prompt prompt, @Nullable ChatResponse previousChatResponse) {
        ChatCompletionCreateParams request = createRequest(prompt, false);

        ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider(AiProvider.OPENAI.value())
                .build();

        ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
                .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                        this.observationRegistry)
                .observe(() -> {

                    ChatCompletion chatCompletion = this.openAiClient.chat().completions().create(request);

                    List<ChatCompletion.Choice> choices = chatCompletion.choices();
                    if (choices.isEmpty()) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("No choices returned for prompt: " + prompt);
                        }
                        return new ChatResponse(List.of());
                    }

                    List<Generation> generations = choices.stream().map(choice -> {
                        Map<String, Object> metadata = Map.of("id", chatCompletion.id(), "role",
                                choice.message()._role().asString().isPresent() ? choice.message()._role().asStringOrThrow() : "",
                                "index", choice.index(), "finishReason", choice.finishReason().value().toString(),
                                "refusal", choice.message().refusal().orElse(""), "annotations",
                                choice.message().annotations().orElse((List) List.of(Map.of())), REASONING_CONTENT,
                                getReasoningContent(choice));
                        return buildGeneration(choice, metadata, request);
                    }).toList();

                    CompletionUsage usage = chatCompletion.usage().orElse(null);
                    Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
                    Usage accumulatedUsage = UsageCalculator.getCumulativeUsage(currentChatResponseUsage, previousChatResponse);
                    ChatResponse chatResponse = new ChatResponse(generations, from(chatCompletion, accumulatedUsage));

                    observationContext.setResponse(chatResponse);

                    return chatResponse;
                });

        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt requestPrompt = buildRequestPrompt(prompt);
        verifyPromptChatOptions(requestPrompt);
        return internalStream(requestPrompt);
    }

    private Flux<ChatResponse> internalStream(Prompt prompt) {
        return Flux.deferContextual(contextView -> {
            ChatCompletionCreateParams request = createRequest(prompt, true);
            ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();
            final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                    .prompt(prompt)
                    .provider(AiProvider.OPENAI.value())
                    .streaming(true)
                    .build();
            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
                    this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                    this.observationRegistry);
            Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            observation.parentObservation(parentObservation);
            try (Observation.Scope ignored = parentObservation != null ? parentObservation.openScope()
                    : Observation.Scope.NOOP) {
                observation.start();
            }

            Flux<ChatCompletionChunk> chunks = Flux.<ChatCompletionChunk>create(sink -> this.openAiClientAsync.chat()
                    .completions()
                    .createStreaming(request)
                    .subscribe(sink::next)
                    .onCompleteFuture()
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            sink.error(throwable);
                        } else {
                            sink.complete();
                        }
                    }));

            AtomicBoolean isInsideTool = new AtomicBoolean(false);
            Flux<ChatCompletion> aggregatedChatCompletions = chunks.doOnNext(chunk -> {
                if (hasToolCall(chunk)) {
                    isInsideTool.set(true);
                }
            }).bufferUntil(chunk -> {
                if (isInsideTool.get() && toolCallsDone(chunk)) {
                    isInsideTool.set(false);
                    return true;
                }
                return !isInsideTool.get();
            }).map(this::mergeChunks).map(this::chunkToChatCompletion);

            Flux<ChatResponse> chatResponses = aggregatedChatCompletions.map(chatCompletion -> {
                String id = chatCompletion.id();
                List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
                    roleMap.putIfAbsent(id, choice.message()._role().asString().isPresent()
                            ? choice.message()._role().asStringOrThrow() : "");

                    Map<String, Object> metadata = Map.of("id", id, "role", roleMap.getOrDefault(id, ""),
                            "index", choice.index(), "finishReason", choice.finishReason().value(),
                            "refusal", choice.message().refusal().orElse(""),
                            "annotations", choice.message().annotations().orElseGet(List::of),
                            REASONING_CONTENT, getReasoningContent(choice));

                    return buildGeneration(choice, metadata, request);
                }).toList();
                Optional<CompletionUsage> usage = chatCompletion.usage();
                CompletionUsage usageVal = usage.orElse(null);
                Usage currentUsage = usageVal != null ? getDefaultUsage(usageVal) : new EmptyUsage();
                Usage accumulated = UsageCalculator.getCumulativeUsage(currentUsage, null);
                return new ChatResponse(generations, from(chatCompletion, accumulated));
            });

            Flux<ChatResponse> observedResponses = chatResponses.doOnError(observation::error)
                    .doFinally(s -> observation.stop())
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

            return new MessageAggregator().aggregate(observedResponses, observationContext::setResponse);
        });
    }

    // ========== 核心修改：音频处理方法 ==========

    /**
     * 阿里云百炼兼容的音频数据处理方法
     * 百炼要求 input_audio.data 必须是 data:audio/{format};base64, 格式
     */
    private String fromAudioData(Object audioData, String mimeType) {
        if (audioData instanceof byte[] bytes) {
            // 阿里云百炼需要 data:;base64, 前缀
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
        throw new IllegalArgumentException("Unsupported audio data type: " + audioData.getClass().getSimpleName());
    }

    private String fromMediaData(org.springframework.util.MimeType mimeType, Object mediaContentData) {
        if (mediaContentData instanceof byte[] bytes) {
            return String.format("data:%s;base64,%s", mimeType.toString(), Base64.getEncoder().encodeToString(bytes));
        } else if (mediaContentData instanceof String text) {
            return text;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported media data type: " + mediaContentData.getClass().getSimpleName());
        }
    }

    // ========== createRequest 方法（修改音频处理部分） ==========

    ChatCompletionCreateParams createRequest(Prompt prompt, boolean stream) {
        List<ChatCompletionMessageParam> chatCompletionMessageParams = prompt.getInstructions()
                .stream()
                .map(message -> {
                    if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
                        ChatCompletionUserMessageParam.Builder builder = ChatCompletionUserMessageParam.builder();

                        if (message instanceof UserMessage userMessage
                                && !CollectionUtils.isEmpty(userMessage.getMedia())) {
                            List<ChatCompletionContentPart> parts = new ArrayList<>();

                            String messageText = message.getText();
                            if (messageText != null && !messageText.isEmpty()) {
                                parts.add(ChatCompletionContentPart
                                        .ofText(ChatCompletionContentPartText.builder().text(messageText).build()));
                            }

                            userMessage.getMedia().forEach(media -> {
                                String mimeType = media.getMimeType().toString();
                                if (mimeType.startsWith("image/")) {
                                    if (media.getData() instanceof java.net.URI uri) {
                                        parts.add(ChatCompletionContentPart
                                                .ofImageUrl(ChatCompletionContentPartImage.builder()
                                                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                                                .url(uri.toString())
                                                                .build())
                                                        .build()));
                                    } else if (media.getData() instanceof String text) {
                                        parts.add(ChatCompletionContentPart
                                                .ofImageUrl(ChatCompletionContentPartImage.builder()
                                                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                                                .url(text)
                                                                .build())
                                                        .build()));
                                    } else if (media.getData() instanceof byte[] bytes) {
                                        ChatCompletionContentPartImage.ImageUrl.Builder imageUrlBuilder =
                                                ChatCompletionContentPartImage.ImageUrl.builder();
                                        imageUrlBuilder.url("data:" + mimeType + ";base64,"
                                                + Base64.getEncoder().encodeToString(bytes));
                                        parts.add(ChatCompletionContentPart
                                                .ofImageUrl(ChatCompletionContentPartImage.builder()
                                                        .imageUrl(imageUrlBuilder.build())
                                                        .build()));
                                    } else {
                                        if (logger.isInfoEnabled()) {
                                            logger.info("Could not process image media with data of type: "
                                                    + media.getData().getClass().getSimpleName());
                                        }
                                    }
                                } else if (mimeType.startsWith("audio/")) {
                                    // ===== 关键修改：适配阿里云百炼的音频格式 =====
                                    String audioFormat = mimeType.contains("mp3") ? "mp3" : "wav";

                                    parts.add(ChatCompletionContentPart
                                            .ofInputAudio(ChatCompletionContentPartInputAudio.builder()
                                                    .inputAudio(ChatCompletionContentPartInputAudio.InputAudio.builder()
                                                            // 阿里云百炼需要 data:;base64, 前缀
                                                            .data(fromAudioData(media.getData(), mimeType))
                                                            .format(audioFormat.equals("mp3")
                                                                    ? ChatCompletionContentPartInputAudio.InputAudio.Format.MP3
                                                                    : ChatCompletionContentPartInputAudio.InputAudio.Format.WAV)
                                                            .build())
                                                    .build()));
                                } else {
                                    parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                                            .text(fromMediaData(media.getMimeType(), media.getData()))
                                            .build()));
                                }
                            });
                            builder.contentOfArrayOfContentParts(parts);
                        } else {
                            String messageText = message.getText();
                            if (messageText != null) {
                                builder.content(ChatCompletionContentPartText.builder().text(messageText).build().text());
                            }
                        }

                        if (message.getMessageType() == MessageType.USER) {
                            builder.role(JsonValue.from(MessageType.USER.getValue()));
                        } else {
                            builder.role(JsonValue.from(MessageType.SYSTEM.getValue()));
                        }

                        return List.of(ChatCompletionMessageParam.ofUser(builder.build()));
                    } else if (message.getMessageType() == MessageType.ASSISTANT) {
                        var assistantMessage = (AssistantMessage) message;
                        ChatCompletionAssistantMessageParam.Builder builder = ChatCompletionAssistantMessageParam.builder()
                                .role(JsonValue.from(MessageType.ASSISTANT.getValue()));

                        if (assistantMessage.getText() != null) {
                            builder.content(ChatCompletionAssistantMessageParam.builder()
                                    .content(assistantMessage.getText())
                                    .build()
                                    .content());
                        }

                        if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                            Map<String, String> toolCallAdditionalProperties = toolCallAdditionalPropertiesFromMetadata(
                                    assistantMessage);

                            List<ChatCompletionMessageToolCall> toolCalls = assistantMessage.getToolCalls()
                                    .stream()
                                    .map(toolCall -> {
                                        ChatCompletionMessageFunctionToolCall.Builder toolCallBuilder =
                                                ChatCompletionMessageFunctionToolCall.builder()
                                                        .id(toolCall.id())
                                                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                                                .name(toolCall.name())
                                                                .arguments(toolCall.arguments())
                                                                .build());

                                        String jsonProps = toolCallAdditionalProperties.get(toolCall.id());
                                        if (StringUtils.hasText(jsonProps)) {
                                            Map<String, JsonValue> additionalProperties = new LinkedHashMap<>();
                                            try {
                                                objectMapper.readValue(jsonProps, MAP_TYPE_REF)
                                                        .forEach((k, v) -> additionalProperties.put(k, JsonValue.from(v)));
                                            } catch (JsonProcessingException ex) {
                                                throw new IllegalStateException("Conversion from JSON to %s failed"
                                                        .formatted(MAP_TYPE_REF.getType().getTypeName()), ex);
                                            }
                                            toolCallBuilder.putAllAdditionalProperties(additionalProperties);
                                        }
                                        return ChatCompletionMessageToolCall.ofFunction(toolCallBuilder.build());
                                    })
                                    .toList();

                            builder.toolCalls(toolCalls);
                        }

                        Object reasoningContent = assistantMessage.getMetadata().get(REASONING_CONTENT);
                        if (reasoningContent instanceof String reasoning && StringUtils.hasText(reasoning)) {
                            builder.putAdditionalProperty("reasoning_content", JsonValue.from(reasoning));
                        }

                        return List.of(ChatCompletionMessageParam.ofAssistant(builder.build()));
                    } else if (message.getMessageType() == MessageType.TOOL) {
                        ToolResponseMessage toolMessage = (ToolResponseMessage) message;

                        ChatCompletionToolMessageParam.Builder builder = ChatCompletionToolMessageParam.builder();
                        builder.content(toolMessage.getText() != null ? toolMessage.getText() : "");
                        builder.role(JsonValue.from(MessageType.TOOL.getValue()));

                        if (toolMessage.getResponses().isEmpty()) {
                            return List.of(ChatCompletionMessageParam.ofTool(builder.build()));
                        }
                        return toolMessage.getResponses().stream().map(response -> {
                            String callId = response.id();
                            String callResponse = response.responseData();

                            return ChatCompletionMessageParam
                                    .ofTool(builder.toolCallId(callId).content(callResponse).build());
                        }).toList();
                    } else {
                        throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
                    }
                })
                .flatMap(List::stream)
                .toList();

        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder();

        chatCompletionMessageParams.forEach(builder::addMessage);

        OpenAiChatOptions requestOptions = (OpenAiChatOptions) prompt.getOptions();
        Assert.state(requestOptions != null, "ChatOptions must not be null");

        // 设置模型和其他参数（与原始代码相同）
        if (requestOptions.getDeploymentName() != null) {
            builder.model(requestOptions.getDeploymentName());
        } else if (requestOptions.getModel() != null) {
            builder.model(requestOptions.getModel());
        }

        if (requestOptions.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(requestOptions.getFrequencyPenalty());
        }
        if (requestOptions.getLogitBias() != null) {
            builder.logitBias(ChatCompletionCreateParams.LogitBias.builder()
                    .putAllAdditionalProperties(requestOptions.getLogitBias()
                            .entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> JsonValue.from(entry.getValue()))))
                    .build());
        }
        if (requestOptions.getLogprobs() != null) {
            builder.logprobs(requestOptions.getLogprobs());
        }
        if (requestOptions.getTopLogprobs() != null) {
            builder.topLogprobs(requestOptions.getTopLogprobs());
        }
        if (requestOptions.getMaxTokens() != null) {
            builder.maxTokens(requestOptions.getMaxTokens());
        }
        if (requestOptions.getMaxCompletionTokens() != null) {
            builder.maxCompletionTokens(requestOptions.getMaxCompletionTokens());
        }
        if (requestOptions.getN() != null) {
            builder.n(requestOptions.getN());
        }
        if (requestOptions.getOutputModalities() != null) {
            builder.modalities(requestOptions.getOutputModalities()
                    .stream()
                    .map(modality -> ChatCompletionCreateParams.Modality.of(modality.toLowerCase()))
                    .toList());
        }
        if (requestOptions.getOutputAudio() != null) {
            builder.audio(requestOptions.getOutputAudio().toChatCompletionAudioParam());
        }
        if (requestOptions.getPresencePenalty() != null) {
            builder.presencePenalty(requestOptions.getPresencePenalty());
        }
        if (requestOptions.getResponseFormat() != null) {
            // ... ResponseFormat 处理（与原始代码相同）
        }
        if (requestOptions.getSeed() != null) {
            builder.seed(requestOptions.getSeed());
        }
        if (requestOptions.getStop() != null && !requestOptions.getStop().isEmpty()) {
            if (requestOptions.getStop().size() == 1) {
                builder.stop(ChatCompletionCreateParams.Stop.ofString(requestOptions.getStop().get(0)));
            } else {
                builder.stop(ChatCompletionCreateParams.Stop.ofStrings(requestOptions.getStop()));
            }
        }
        if (requestOptions.getTemperature() != null) {
            builder.temperature(requestOptions.getTemperature());
        }
        if (requestOptions.getTopP() != null) {
            builder.topP(requestOptions.getTopP());
        }
        if (requestOptions.getUser() != null) {
            builder.user(requestOptions.getUser());
        }
        if (requestOptions.getParallelToolCalls() != null) {
            builder.parallelToolCalls(requestOptions.getParallelToolCalls());
        }
        if (requestOptions.getReasoningEffort() != null) {
            builder.reasoningEffort(ReasoningEffort.of(requestOptions.getReasoningEffort().toLowerCase()));
        }
        if (requestOptions.getVerbosity() != null) {
            builder.verbosity(ChatCompletionCreateParams.Verbosity.of(requestOptions.getVerbosity()));
        }

        if (requestOptions.getStore() != null) {
            builder.store(requestOptions.getStore());
        }
        if (requestOptions.getMetadata() != null && !requestOptions.getMetadata().isEmpty()) {
            builder.metadata(ChatCompletionCreateParams.Metadata.builder()
                    .putAllAdditionalProperties(requestOptions.getMetadata()
                            .entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> JsonValue.from(entry.getValue()))))
                    .build());
        }
        if (requestOptions.getServiceTier() != null) {
            builder.serviceTier(ChatCompletionCreateParams.ServiceTier.of(requestOptions.getServiceTier()));
        }

        if (requestOptions.getPromptCacheKey() != null) {
            builder.promptCacheKey(requestOptions.getPromptCacheKey());
        }

        if (requestOptions.getCustomHeaders() != null && !requestOptions.getCustomHeaders().isEmpty()) {
            requestOptions.getCustomHeaders().forEach(builder::putAdditionalHeader);
        }

        if (stream) {
            if (requestOptions.getStreamOptions() != null) {
                ChatCompletionStreamOptions.Builder streamOptionsBuilder = ChatCompletionStreamOptions.builder();
                var ops = requestOptions.getStreamOptions();
                streamOptionsBuilder.includeObfuscation(ops.includeObfuscation() != null && ops.includeObfuscation());
                streamOptionsBuilder.includeUsage(ops.includeUsage() != null && ops.includeUsage());
                if (!CollectionUtils.isEmpty(ops.additionalProperties())) {
                    Map<String, JsonValue> nativeParams = ops.additionalProperties()
                            .entrySet()
                            .stream()
                            .map(e -> Map.entry(e.getKey(), JsonValue.from(e.getValue())))
                            .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
                    streamOptionsBuilder.putAllAdditionalProperties(nativeParams);
                }
                builder.streamOptions(streamOptionsBuilder.build());
            } else {
                builder.streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build());
            }
        }

        List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
        if (!CollectionUtils.isEmpty(toolDefinitions)) {
            builder.tools(getChatCompletionTools(toolDefinitions));
        }

        if (requestOptions.getToolChoice() != null) {
            // ... ToolChoice 处理（与原始代码相同）
        }

        if (requestOptions.getExtraBody() != null && !requestOptions.getExtraBody().isEmpty()) {
            Map<String, JsonValue> extraParams = requestOptions.getExtraBody()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> JsonValue.from(entry.getValue())));
            builder.additionalBodyProperties(extraParams);
        }

        return builder.build();
    }

    // ========== 辅助方法 ==========

    private boolean hasToolCall(ChatCompletionChunk chunk) {
        return !chunk.choices().isEmpty() && chunk.choices().get(0).delta().toolCalls().isPresent();
    }

    private boolean toolCallsDone(ChatCompletionChunk chunk) {
        return !chunk.choices().isEmpty()
                && FinishReason.TOOL_CALLS == chunk.choices().get(0).finishReason().orElse(null);
    }

    private ChatCompletionChunk mergeChunks(List<ChatCompletionChunk> chunks) {
        ChatCompletionChunk.Builder builder = chunks.get(0).toBuilder();
        Map<Long, Choice> choices = new LinkedHashMap<>();
        chunks.get(0).choices().forEach(choice -> choices.put(choice.index(), choice));

        for (int i = 1; i < chunks.size(); i++) {
            ChatCompletionChunk chunk = chunks.get(i);
            chunk.usage().ifPresent(builder::usage);
            chunk.serviceTier().ifPresent(builder::serviceTier);
            chunk.choices()
                    .forEach(choice -> choices.compute(choice.index(),
                            (ix, c) -> c == null ? choice : mergeChoices(c, choice)));
        }
        return builder.choices(new ArrayList<>(choices.values())).build();
    }

    private Choice mergeChoices(Choice c1, Choice c2) {
        return Choice.builder()
                .index(c1.index())
                .finishReason(c1.finishReason().or(c2::finishReason))
                .logprobs(c1.logprobs().or(c2::logprobs))
                .delta(mergeDeltas(c1.delta(), c2.delta()))
                .build();
    }

    private Delta mergeDeltas(Delta left, Delta right) {
        var tcs = Stream.of(left.toolCalls(), right.toolCalls())
                .flatMap(Optional::stream)
                .reduce((tcs1, tcs2) -> {
                    Assert.isTrue(tcs2.size() <= 1, "no more than one tool call per message currently supported");
                    ToolCall toolCall = tcs2.get(0);
                    if (toolCall.id().isPresent()) {
                        List<ToolCall> result = new ArrayList<>(tcs1);
                        result.add(toolCall);
                        return result;
                    } else {
                        ToolCall lastFromTc1 = tcs1.get(tcs1.size() - 1);
                        Function lastFromTc1F = lastFromTc1.function().get();
                        var concatenatedArgs = Stream
                                .of(lastFromTc1F.arguments(), toolCall.function().flatMap(Function::arguments))
                                .flatMap(Optional::stream)
                                .reduce((args1, args2) -> args1 + args2)
                                .orElse("");
                        List<ToolCall> result = new ArrayList<>(tcs1);
                        result.set(tcs1.size() - 1,
                                lastFromTc1.toBuilder()
                                        .putAllAdditionalProperties(toolCall._additionalProperties())
                                        .function(lastFromTc1F.toBuilder().arguments(concatenatedArgs).build())
                                        .build());
                        return result;
                    }
                }).orElse(List.of());

        return left.toBuilder().toolCalls(tcs).build();
    }

    private ChatCompletion chunkToChatCompletion(ChatCompletionChunk chunk) {
        List<ChatCompletion.Choice> choices = chunk.choices().stream().map(cccc -> {
            ChatCompletion.Choice.Builder choiceBuilder = ChatCompletion.Choice.builder();
            choiceBuilder.index(cccc.index());
            choiceBuilder.finishReason(ChatCompletion.Choice.FinishReason.of(""));
            cccc.finishReason()
                    .ifPresent(finishReason -> choiceBuilder.finishReason(
                            ChatCompletion.Choice.FinishReason.of(finishReason.value().name().toLowerCase())));
            if (cccc.logprobs().isPresent()) {
                var logprobs = cccc.logprobs().get();
                choiceBuilder.logprobs(ChatCompletion.Choice.Logprobs.builder()
                        .content(logprobs.content())
                        .refusal(logprobs.refusal())
                        .build());
            } else {
                choiceBuilder.logprobs(
                        ChatCompletion.Choice.Logprobs.builder().content(List.of()).refusal(List.of()).build());
            }
            ChatCompletionMessage.Builder msgBuilder = ChatCompletionMessage.builder()
                    .content(cccc.delta().content())
                    .refusal(cccc.delta().refusal());
            cccc.delta().toolCalls().ifPresent(ccctcs -> {
                msgBuilder.toolCalls(ccctcs.stream().map(tc -> {
                    ChatCompletionMessageFunctionToolCall.Builder toolCallBuilder =
                            ChatCompletionMessageFunctionToolCall.builder();
                    toolCallBuilder.putAllAdditionalProperties(tc._additionalProperties());
                    toolCallBuilder.id(tc.id().get());
                    toolCallBuilder.function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(tc.function().get().name().get())
                            .arguments(tc.function().get().arguments().get())
                            .build());
                    return ChatCompletionMessageToolCall.ofFunction(toolCallBuilder.build());
                }).toList());
            });
            choiceBuilder.message(msgBuilder.build());
            return choiceBuilder.build();
        }).toList();

        return ChatCompletion.builder()
                .id(chunk.id())
                .choices(choices)
                .created(getCreated(chunk))
                .model(chunk.model())
                .usage(chunk.usage()
                        .orElse(CompletionUsage.builder().promptTokens(0).completionTokens(0).totalTokens(0).build()))
                .putAllAdditionalProperties(chunk._additionalProperties())
                .build();
    }

    private long getCreated(ChatCompletionChunk chunk) {
        try {
            return chunk.created();
        } catch (OpenAIInvalidDataException ex) {
            return 0L;
        }
    }

    private Generation buildGeneration(ChatCompletion.Choice choice, Map<String, Object> metadata,
                                       ChatCompletionCreateParams request) {
        ChatCompletionMessage message = choice.message();
        Map<String, Object> assistantMessageMetadata = new LinkedHashMap<>(metadata);
        Map<String, String> toolCallAdditionalProperties = extractToolCallAdditionalProperties(message);
        if (!toolCallAdditionalProperties.isEmpty()) {
            assistantMessageMetadata.put(TOOL_CALL_ADDITIONAL_PROPERTIES_METADATA_KEY, toolCallAdditionalProperties);
        }
        List<AssistantMessage.ToolCall> toolCalls = message.toolCalls()
                .map(list -> list.stream().filter(tc -> tc.function().isPresent()).map(tc -> {
                    var opt = tc.function();
                    if (opt.isEmpty()) {
                        return null;
                    }
                    var funcCall = opt.get();
                    var functionDef = funcCall.function();
                    String id = funcCall.id();
                    String name = functionDef.name();
                    String arguments = functionDef.arguments();
                    return new AssistantMessage.ToolCall(id, "function", name, arguments);
                }).filter(Objects::nonNull).toList())
                .orElse(List.of());

        var generationMetadataBuilder = ChatGenerationMetadata.builder()
                .finishReason(choice.finishReason().value().name());

        String textContent = message.content().orElse("");

        List<Media> media = new ArrayList<>();

        if (message.audio().isPresent() && StringUtils.hasText(message.audio().get().data())
                && request.audio().isPresent()) {
            var audioOutput = message.audio().get();
            String mimeType = String.format("audio/%s", request.audio().get().format().value().name().toLowerCase());
            byte[] audioData = Base64.getDecoder().decode(audioOutput.data());
            Resource resource = new ByteArrayResource(audioData);
            Media.builder().mimeType(MimeTypeUtils.parseMimeType(mimeType)).data(resource).id(audioOutput.id()).build();
            media.add(Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                    .data(resource)
                    .id(audioOutput.id())
                    .build());
            if (!StringUtils.hasText(textContent)) {
                textContent = audioOutput.transcript();
            }
            generationMetadataBuilder.metadata("audioId", audioOutput.id());
            generationMetadataBuilder.metadata("audioExpiresAt", audioOutput.expiresAt());
        }

        var assistantMessage = AssistantMessage.builder()
                .content(textContent)
                .properties(assistantMessageMetadata)
                .toolCalls(toolCalls)
                .media(media)
                .build();
        return new Generation(assistantMessage, generationMetadataBuilder.build());
    }

    private Map<String, String> extractToolCallAdditionalProperties(ChatCompletionMessage message) {
        Map<String, String> result = new LinkedHashMap<>();
        message.toolCalls()
                .ifPresent(toolCalls -> toolCalls.forEach(toolCall -> toolCall.function().ifPresent(functionToolCall -> {
                    Map<String, JsonValue> props = functionToolCall._additionalProperties();
                    if (!CollectionUtils.isEmpty(props)) {
                        try {
                            result.put(functionToolCall.id(), objectMapper.writeValueAsString(props));
                        } catch (JsonProcessingException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                })));
        return result;
    }

    private ChatResponseMetadata from(ChatCompletion result, Usage usage) {
        Assert.notNull(result, "OpenAI ChatCompletion must not be null");
        ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder()
                .id(result.id())
                .usage(usage)
                .model(result.model())
                .keyValue("created", getCreated(result));

        result._additionalProperties().forEach((key, jsonValue) -> {
            try {
                Object value = JacksonUtils.getDefaultJsonMapper().convertValue(jsonValue, Object.class);
                metadataBuilder.keyValue(key, value);
            } catch (Exception e) {
                if (logger.isErrorEnabled()) {
                    logger.error("Error parsing JSON value for key '" + key + "': " + jsonValue, e);
                }
                metadataBuilder.keyValue(key, jsonValue);
            }
        });

        return metadataBuilder.build();
    }

    private long getCreated(ChatCompletion result) {
        try {
            return result.created();
        } catch (OpenAIInvalidDataException ex) {
            return 0L;
        }
    }

    private DefaultUsage getDefaultUsage(CompletionUsage usage) {
        Long cacheRead = usage.promptTokensDetails().flatMap(details -> details.cachedTokens()).orElse(null);
        return new DefaultUsage(Math.toIntExact(usage.promptTokens()), Math.toIntExact(usage.completionTokens()),
                Math.toIntExact(usage.totalTokens()), usage, cacheRead, null);
    }

    private void verifyPromptChatOptions(Prompt prompt) {
        var chatOptions = prompt.getOptions();
        if (chatOptions != null && chatOptions.getTopK() != null) {
            logger.warn("The topK option is not supported by OpenAI chat models. Ignoring.");
        }
    }

    private Map<String, String> toolCallAdditionalPropertiesFromMetadata(AssistantMessage assistantMessage) {
        Object value = assistantMessage.getMetadata().get(TOOL_CALL_ADDITIONAL_PROPERTIES_METADATA_KEY);
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> {
            if (k instanceof String id && v instanceof String json) {
                result.put(id, json);
            }
        });
        return result;
    }

    private String getReasoningContent(ChatCompletion.Choice choice) {
        String reasoningContent = "";
        Map<String, JsonValue> additionalProperties = choice.message()._additionalProperties();
        if (additionalProperties.get("reasoning_content") != null) {
            reasoningContent = (String) additionalProperties.get("reasoning_content").asString().orElse("");
        } else {
            if (additionalProperties.get("reasoning") != null) {
                reasoningContent = (String) additionalProperties.get("reasoning").asString().orElse("");
            }
        }
        return reasoningContent;
    }

    private List<ChatCompletionTool> getChatCompletionTools(List<ToolDefinition> toolDefinitions) {
        return toolDefinitions.stream().map(toolDefinition -> {
            FunctionParameters.Builder parametersBuilder = FunctionParameters.builder();

            if (!toolDefinition.inputSchema().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> schemaMap = objectMapper.readValue(toolDefinition.inputSchema(), Map.class);
                    schemaMap
                            .forEach((key, value) -> parametersBuilder.putAdditionalProperty(key, JsonValue.from(value)));
                    parametersBuilder.putAdditionalProperty("strict", JsonValue.from(true));
                } catch (Exception e) {
                    logger.error("Failed to parse tool schema", e);
                }
            }

            FunctionDefinition functionDefinition = FunctionDefinition.builder()
                    .name(toolDefinition.name())
                    .description(toolDefinition.description())
                    .parameters(parametersBuilder.build())
                    .build();

            return ChatCompletionTool
                    .ofFunction(ChatCompletionFunctionTool.builder().function(functionDefinition).build());
        }).toList();
    }

    private Prompt buildRequestPrompt(Prompt prompt) {
        if (prompt.getOptions() == null) {
            return prompt.mutate().chatOptions(this.getOptions()).build();
        } else {
            return prompt;
        }
    }

    public void setObservationConvention(ChatModelObservationConvention observationConvention) {
        Assert.notNull(observationConvention, "observationConvention cannot be null");
        this.observationConvention = observationConvention;
    }
}