/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.ollama;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.AbstractToolCallSupport;
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
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.ai.ollama.api.OllamaApi.Message.Role;
import org.springframework.ai.ollama.api.OllamaApi.Message.ToolCall;
import org.springframework.ai.ollama.api.OllamaApi.Message.ToolCallFunction;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.OllamaModelManager;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ChatModel} implementation for {@literal Ollama}. Ollama allows developers to run
 * large language models and generate embeddings locally. It supports open-source models
 * available on [Ollama AI Library](<a href="https://ollama.ai/library">...</a>) and on
 * Hugging Face. Please refer to the <a href="https://ollama.ai/">official Ollama
 * website</a> for the most up-to-date information on available models.
 *
 * @author Christian Tzolov
 * @author luocongqiu
 * @author Thomas Vitale
 * @author Jihoon Kim
 * @author Alexandros Pappas
 * @author Ilayaperumal Gopinathan
 * @since 1.0.0
 */
public class OllamaChatModel extends AbstractToolCallSupport implements ChatModel {

	private static final String DONE = "done";

	private static final String METADATA_PROMPT_EVAL_COUNT = "prompt-eval-count";

	private static final String METADATA_EVAL_COUNT = "eval-count";

	private static final String METADATA_CREATED_AT = "created-at";

	private static final String METADATA_TOTAL_DURATION = "total-duration";

	private static final String METADATA_LOAD_DURATION = "load-duration";

	private static final String METADATA_PROMPT_EVAL_DURATION = "prompt-eval-duration";

	private static final String METADATA_EVAL_DURATION = "eval-duration";

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private final OllamaApi chatApi;

	private final OllamaOptions defaultOptions;

	private final ObservationRegistry observationRegistry;

	private final OllamaModelManager modelManager;

	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	public OllamaChatModel(OllamaApi ollamaApi, OllamaOptions defaultOptions,
			FunctionCallbackResolver functionCallbackResolver, List<FunctionCallback> toolFunctionCallbacks,
			ObservationRegistry observationRegistry, ModelManagementOptions modelManagementOptions) {
		super(functionCallbackResolver, defaultOptions, toolFunctionCallbacks);
		Assert.notNull(ollamaApi, "ollamaApi must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(modelManagementOptions, "modelManagementOptions must not be null");
		this.chatApi = ollamaApi;
		this.defaultOptions = defaultOptions;
		this.observationRegistry = observationRegistry;
		this.modelManager = new OllamaModelManager(this.chatApi, modelManagementOptions);
		initializeModel(defaultOptions.getModel(), modelManagementOptions.pullModelStrategy());
	}

	public static Builder builder() {
		return new Builder();
	}

	static ChatResponseMetadata from(OllamaApi.ChatResponse response, ChatResponse previousChatResponse) {
		Assert.notNull(response, "OllamaApi.ChatResponse must not be null");

		DefaultUsage newUsage = getDefaultUsage(response);
		Integer promptTokens = newUsage.getPromptTokens();
		Integer generationTokens = newUsage.getCompletionTokens();
		int totalTokens = newUsage.getTotalTokens();

		Duration evalDuration = response.getEvalDuration();
		Duration promptEvalDuration = response.getPromptEvalDuration();
		Duration loadDuration = response.getLoadDuration();
		Duration totalDuration = response.getTotalDuration();

		if (previousChatResponse != null && previousChatResponse.getMetadata() != null) {
			if (previousChatResponse.getMetadata().get(METADATA_EVAL_DURATION) != null) {
				evalDuration = evalDuration.plus(previousChatResponse.getMetadata().get(METADATA_EVAL_DURATION));
			}
			if (previousChatResponse.getMetadata().get(METADATA_PROMPT_EVAL_DURATION) != null) {
				promptEvalDuration = promptEvalDuration
					.plus(previousChatResponse.getMetadata().get(METADATA_PROMPT_EVAL_DURATION));
			}
			if (previousChatResponse.getMetadata().get(METADATA_LOAD_DURATION) != null) {
				loadDuration = loadDuration.plus(previousChatResponse.getMetadata().get(METADATA_LOAD_DURATION));
			}
			if (previousChatResponse.getMetadata().get(METADATA_TOTAL_DURATION) != null) {
				totalDuration = totalDuration.plus(previousChatResponse.getMetadata().get(METADATA_TOTAL_DURATION));
			}
			if (previousChatResponse.getMetadata().getUsage() != null) {
				promptTokens += previousChatResponse.getMetadata().getUsage().getPromptTokens();
				generationTokens += previousChatResponse.getMetadata().getUsage().getCompletionTokens();
				totalTokens += previousChatResponse.getMetadata().getUsage().getTotalTokens();
			}
		}

		DefaultUsage aggregatedUsage = new DefaultUsage(promptTokens, generationTokens, totalTokens);

		return ChatResponseMetadata.builder()
			.usage(aggregatedUsage)
			.model(response.model())
			.keyValue(METADATA_CREATED_AT, response.createdAt())
			.keyValue(METADATA_EVAL_DURATION, evalDuration)
			.keyValue(METADATA_EVAL_COUNT, aggregatedUsage.getCompletionTokens().intValue())
			.keyValue(METADATA_LOAD_DURATION, loadDuration)
			.keyValue(METADATA_PROMPT_EVAL_DURATION, promptEvalDuration)
			.keyValue(METADATA_PROMPT_EVAL_COUNT, aggregatedUsage.getPromptTokens().intValue())
			.keyValue(METADATA_TOTAL_DURATION, totalDuration)
			.keyValue(DONE, response.done())
			.build();
	}

	private static DefaultUsage getDefaultUsage(OllamaApi.ChatResponse response) {
		return new DefaultUsage(Optional.ofNullable(response.promptEvalCount()).orElse(0),
				Optional.ofNullable(response.evalCount()).orElse(0));
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		return this.internalCall(prompt, null);
	}

	private ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

		OllamaApi.ChatRequest request = ollamaChatRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(OllamaApi.PROVIDER_NAME)
			.requestOptions(buildRequestOptions(request))
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {

				OllamaApi.ChatResponse ollamaResponse = this.chatApi.chat(request);

				List<AssistantMessage.ToolCall> toolCalls = ollamaResponse.message().toolCalls() == null ? List.of()
						: ollamaResponse.message()
							.toolCalls()
							.stream()
							.map(toolCall -> new AssistantMessage.ToolCall("", "function", toolCall.function().name(),
									ModelOptionsUtils.toJsonString(toolCall.function().arguments())))
							.toList();

				var assistantMessage = new AssistantMessage(ollamaResponse.message().content(), Map.of(), toolCalls);

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;
				if (ollamaResponse.promptEvalCount() != null && ollamaResponse.evalCount() != null) {
					generationMetadata = ChatGenerationMetadata.builder()
						.finishReason(ollamaResponse.doneReason())
						.build();
				}

				var generator = new Generation(assistantMessage, generationMetadata);
				ChatResponse chatResponse = new ChatResponse(List.of(generator),
						from(ollamaResponse, previousChatResponse));

				observationContext.setResponse(chatResponse);

				return chatResponse;

			});

		if (!isProxyToolCalls(prompt, this.defaultOptions) && response != null
				&& isToolCall(response, Set.of("stop"))) {
			var toolCallConversation = handleToolCalls(prompt, response);
			// Recursively call the call method with the tool call message
			// conversation that contains the call responses.
			return this.internalCall(new Prompt(toolCallConversation, prompt.getOptions()), response);
		}

		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return this.internalStream(prompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			OllamaApi.ChatRequest request = ollamaChatRequest(prompt, true);

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(OllamaApi.PROVIDER_NAME)
				.requestOptions(buildRequestOptions(request))
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<OllamaApi.ChatResponse> ollamaResponse = this.chatApi.streamingChat(request);

			Flux<ChatResponse> chatResponse = ollamaResponse.map(chunk -> {
				String content = (chunk.message() != null) ? chunk.message().content() : "";

				List<AssistantMessage.ToolCall> toolCalls = List.of();

				// Added null checks to prevent NPE when accessing tool calls
				if (chunk.message() != null && chunk.message().toolCalls() != null) {
					toolCalls = chunk.message()
						.toolCalls()
						.stream()
						.map(toolCall -> new AssistantMessage.ToolCall("", "function", toolCall.function().name(),
								ModelOptionsUtils.toJsonString(toolCall.function().arguments())))
						.toList();
				}

				var assistantMessage = new AssistantMessage(content, Map.of(), toolCalls);

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;
				if (chunk.promptEvalCount() != null && chunk.evalCount() != null) {
					generationMetadata = ChatGenerationMetadata.builder().finishReason(chunk.doneReason()).build();
				}

				var generator = new Generation(assistantMessage, generationMetadata);
				return new ChatResponse(List.of(generator), from(chunk, previousChatResponse));
			});

			// @formatter:off
			Flux<ChatResponse> chatResponseFlux = chatResponse.flatMap(response -> {
				if (isToolCall(response, Set.of("stop"))) {
					var toolCallConversation = handleToolCalls(prompt, response);
					// Recursively call the stream method with the tool call message
					// conversation that contains the call responses.
					return this.internalStream(new Prompt(toolCallConversation, prompt.getOptions()), response);
				}
				else {
					return Flux.just(response);
				}
			})
			.doOnError(observation::error)
			.doFinally(s ->
				observation.stop()
			)
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
			// @formatter:on

			return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
		});
	}

	/**
	 * Package access for testing.
	 */
	OllamaApi.ChatRequest ollamaChatRequest(Prompt prompt, boolean stream) {

		List<OllamaApi.Message> ollamaMessages = prompt.getInstructions().stream().map(message -> {
			if (message instanceof UserMessage userMessage) {
				var messageBuilder = OllamaApi.Message.builder(Role.USER).content(message.getText());
				if (!CollectionUtils.isEmpty(userMessage.getMedia())) {
					messageBuilder.images(
							userMessage.getMedia().stream().map(media -> this.fromMediaData(media.getData())).toList());
				}
				return List.of(messageBuilder.build());
			}
			else if (message instanceof SystemMessage systemMessage) {
				return List.of(OllamaApi.Message.builder(Role.SYSTEM).content(systemMessage.getText()).build());
			}
			else if (message instanceof AssistantMessage assistantMessage) {
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ToolCallFunction(toolCall.name(),
								ModelOptionsUtils.jsonToMap(toolCall.arguments()));
						return new ToolCall(function);
					}).toList();
				}
				return List.of(OllamaApi.Message.builder(Role.ASSISTANT)
					.content(assistantMessage.getText())
					.toolCalls(toolCalls)
					.build());
			}
			else if (message instanceof ToolResponseMessage toolMessage) {
				return toolMessage.getResponses()
					.stream()
					.map(tr -> OllamaApi.Message.builder(Role.TOOL).content(tr.responseData()).build())
					.toList();
			}
			throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
		}).flatMap(List::stream).toList();

		Set<String> functionsForThisRequest = new HashSet<>();

		// runtime options
		OllamaOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof FunctionCallingOptions functionCallingOptions) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(functionCallingOptions, FunctionCallingOptions.class,
						OllamaOptions.class);
			}
			else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
						OllamaOptions.class);
			}
			functionsForThisRequest.addAll(this.runtimeFunctionCallbackConfigurations(runtimeOptions));
		}

		if (!CollectionUtils.isEmpty(this.defaultOptions.getFunctions())) {
			functionsForThisRequest.addAll(this.defaultOptions.getFunctions());
		}
		OllamaOptions mergedOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions, OllamaOptions.class);

		// Override the model.
		if (!StringUtils.hasText(mergedOptions.getModel())) {
			throw new IllegalArgumentException("Model is not set!");
		}

		String model = mergedOptions.getModel();
		OllamaApi.ChatRequest.Builder requestBuilder = OllamaApi.ChatRequest.builder(model)
			.stream(stream)
			.messages(ollamaMessages)
			.options(mergedOptions);

		if (mergedOptions.getFormat() != null) {
			requestBuilder.format(mergedOptions.getFormat());
		}

		if (mergedOptions.getKeepAlive() != null) {
			requestBuilder.keepAlive(mergedOptions.getKeepAlive());
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {
			requestBuilder.tools(this.getFunctionTools(functionsForThisRequest));
		}

		return requestBuilder.build();
	}

	private String fromMediaData(Object mediaData) {
		if (mediaData instanceof byte[] bytes) {
			return Base64.getEncoder().encodeToString(bytes);
		}
		else if (mediaData instanceof String text) {
			return text;
		}
		else {
			throw new IllegalArgumentException("Unsupported media data type: " + mediaData.getClass().getSimpleName());
		}

	}

	private List<ChatRequest.Tool> getFunctionTools(Set<String> functionNames) {
		return this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			var function = new ChatRequest.Tool.Function(functionCallback.getName(), functionCallback.getDescription(),
					functionCallback.getInputTypeSchema());
			return new ChatRequest.Tool(function);
		}).toList();
	}

	private ChatOptions buildRequestOptions(OllamaApi.ChatRequest request) {
		var options = ModelOptionsUtils.mapToClass(request.options(), OllamaOptions.class);
		return ChatOptions.builder()
			.model(request.model())
			.frequencyPenalty(options.getFrequencyPenalty())
			.maxTokens(options.getMaxTokens())
			.presencePenalty(options.getPresencePenalty())
			.stopSequences(options.getStopSequences())
			.temperature(options.getTemperature())
			.topK(options.getTopK())
			.topP(options.getTopP())
			.build();
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return OllamaOptions.fromOptions(this.defaultOptions);
	}

	/**
	 * Pull the given model into Ollama based on the specified strategy.
	 */
	private void initializeModel(String model, PullModelStrategy pullModelStrategy) {
		if (pullModelStrategy != null && !PullModelStrategy.NEVER.equals(pullModelStrategy)) {
			this.modelManager.pullModel(model, pullModelStrategy);
		}
	}

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	public static final class Builder {

		private OllamaApi ollamaApi;

		private OllamaOptions defaultOptions = OllamaOptions.builder().model(OllamaModel.MISTRAL.id()).build();

		private FunctionCallbackResolver functionCallbackResolver;

		private List<FunctionCallback> toolFunctionCallbacks = List.of();

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private ModelManagementOptions modelManagementOptions = ModelManagementOptions.defaults();

		private Builder() {
		}

		public Builder ollamaApi(OllamaApi ollamaApi) {
			this.ollamaApi = ollamaApi;
			return this;
		}

		public Builder defaultOptions(OllamaOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public Builder functionCallbackResolver(FunctionCallbackResolver functionCallbackResolver) {
			this.functionCallbackResolver = functionCallbackResolver;
			return this;
		}

		public Builder toolFunctionCallbacks(List<FunctionCallback> toolFunctionCallbacks) {
			this.toolFunctionCallbacks = toolFunctionCallbacks;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder modelManagementOptions(ModelManagementOptions modelManagementOptions) {
			this.modelManagementOptions = modelManagementOptions;
			return this;
		}

		public OllamaChatModel build() {
			return new OllamaChatModel(this.ollamaApi, this.defaultOptions, this.functionCallbackResolver,
					this.toolFunctionCallbacks, this.observationRegistry, this.modelManagementOptions);
		}

	}

}
