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

package org.springframework.ai.bedrock.anthropic3.api;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import org.springframework.ai.bedrock.RequiresAwsCredentials;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.AnthropicChatModel;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.AnthropicChatRequest;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.AnthropicChatResponse;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.AnthropicChatStreamingResponse.StreamingType;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.ChatCompletionMessage;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.ChatCompletionMessage.Role;
import org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.MediaContent;
import org.springframework.core.log.LogAccessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Ben Middleton
 */
@RequiresAwsCredentials
public class Anthropic3ChatBedrockApiIT {

	private static final LogAccessor logger = new LogAccessor(Anthropic3ChatBedrockApiIT.class);

	private Anthropic3ChatBedrockApi anthropicChatApi = new Anthropic3ChatBedrockApi(
			AnthropicChatModel.CLAUDE_INSTANT_V1.id(), EnvironmentVariableCredentialsProvider.create(),
			Region.US_EAST_1.id(), new ObjectMapper(), Duration.ofMinutes(2));

	@Test
	public void chatCompletion() {

		MediaContent anthropicMessage = new MediaContent("Name 3 famous pirates");
		ChatCompletionMessage chatCompletionMessage = new ChatCompletionMessage(List.of(anthropicMessage), Role.USER);
		AnthropicChatRequest request = AnthropicChatRequest.builder(List.of(chatCompletionMessage))
			.temperature(0.8)
			.maxTokens(300)
			.topK(10)
			.anthropicVersion(
					org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.DEFAULT_ANTHROPIC_VERSION)
			.build();

		AnthropicChatResponse response = this.anthropicChatApi.chatCompletion(request);

		logger.info("" + response.content());

		assertThat(response).isNotNull();
		assertThat(response.content().get(0).text()).isNotEmpty();
		assertThat(response.content().get(0).text()).contains("Blackbeard");
		assertThat(response.stopReason()).isEqualTo("end_turn");
		assertThat(response.stopSequence()).isNull();
		assertThat(response.usage().inputTokens()).isGreaterThan(10);
		assertThat(response.usage().outputTokens()).isGreaterThan(100);

		logger.info("" + response);
	}

	@Test
	public void chatMultiCompletion() {

		MediaContent anthropicInitialMessage = new MediaContent("Name 3 famous pirates");
		ChatCompletionMessage chatCompletionInitialMessage = new ChatCompletionMessage(List.of(anthropicInitialMessage),
				Role.USER);

		MediaContent anthropicAssistantMessage = new MediaContent(
				"Here are 3 famous pirates: Blackbeard, Calico Jack, Henry Morgan");
		ChatCompletionMessage chatCompletionAssistantMessage = new ChatCompletionMessage(
				List.of(anthropicAssistantMessage), Role.ASSISTANT);

		MediaContent anthropicFollowupMessage = new MediaContent("Why are they famous?");
		ChatCompletionMessage chatCompletionFollowupMessage = new ChatCompletionMessage(
				List.of(anthropicFollowupMessage), Role.USER);

		AnthropicChatRequest request = AnthropicChatRequest
			.builder(List.of(chatCompletionInitialMessage, chatCompletionAssistantMessage,
					chatCompletionFollowupMessage))
			.temperature(0.8)
			.maxTokens(400)
			.topK(10)
			.anthropicVersion(
					org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.DEFAULT_ANTHROPIC_VERSION)
			.build();

		AnthropicChatResponse response = this.anthropicChatApi.chatCompletion(request);

		logger.info("" + response.content());
		assertThat(response).isNotNull();
		assertThat(response.content().get(0).text()).isNotEmpty();
		assertThat(response.content().get(0).text()).contains("Blackbeard");
		assertThat(response.stopReason()).isEqualTo("end_turn");
		assertThat(response.stopSequence()).isNull();
		assertThat(response.usage().inputTokens()).isGreaterThan(30);
		assertThat(response.usage().outputTokens()).isGreaterThan(200);

		logger.info("" + response);
	}

	@Test
	public void chatCompletionStream() {
		MediaContent anthropicMessage = new MediaContent("Name 3 famous pirates");
		ChatCompletionMessage chatCompletionMessage = new ChatCompletionMessage(List.of(anthropicMessage), Role.USER);

		AnthropicChatRequest request = AnthropicChatRequest.builder(List.of(chatCompletionMessage))
			.temperature(0.8)
			.maxTokens(300)
			.topK(10)
			.anthropicVersion(
					org.springframework.ai.bedrock.anthropic3.api.Anthropic3ChatBedrockApi.DEFAULT_ANTHROPIC_VERSION)
			.build();

		Flux<Anthropic3ChatBedrockApi.AnthropicChatStreamingResponse> responseStream = this.anthropicChatApi
			.chatCompletionStream(request);

		List<Anthropic3ChatBedrockApi.AnthropicChatStreamingResponse> responses = responseStream.collectList().block();
		assertThat(responses).isNotNull();
		assertThat(responses).hasSizeGreaterThan(10);
		assertThat(responses.stream()
			.filter(message -> message.type() == StreamingType.CONTENT_BLOCK_DELTA)
			.map(message -> message.delta().text())
			.collect(Collectors.joining())).contains("Blackbeard");
	}

}
