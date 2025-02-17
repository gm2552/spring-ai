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

package org.springframework.ai.autoconfigure.openai.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.log.LogAccessor;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*")
public class FunctionCallbackInPrompt2IT {

	private static final LogAccessor logger = new LogAccessor(FunctionCallbackInPromptIT.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withPropertyValues("spring.ai.openai.apiKey=" + System.getenv("OPENAI_API_KEY"))
		.withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class));

	@Test
	void functionCallTest() {
		this.contextRunner.withPropertyValues("spring.ai.openai.chat.options.model=" + ChatModel.GPT_4_O_MINI.getName())
			.run(context -> {

				OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);

				ChatClient chatClient = ChatClient.builder(chatModel).build();

			// @formatter:off
			chatClient.prompt()
					.user("Tell me a joke?")
					.call().content();

			String content = ChatClient.builder(chatModel).build().prompt()
					.user("What's the weather like in San Francisco, Tokyo, and Paris?")
					.functions(FunctionCallback.builder()
							.function("CurrentWeatherService", new MockWeatherService())
							.description("Get the weather in location")
							.inputType(MockWeatherService.Request.class)
							.build())
					.call().content();
			// @formatter:on

				logger.info("Response: " + content);

				assertThat(content).contains("30", "10", "15");
			});
	}

	@Test
	void lambdaFunctionCallTest() {
		Map<String, Object> state = new ConcurrentHashMap<>();

		record LightInfo(String roomName, boolean isOn) {
		}

		this.contextRunner.run(context -> {

			OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);

			// @formatter:off
			String content = ChatClient.builder(chatModel).build().prompt()
					.user("Turn the light on in the kitchen and in the living room!")
					.functions(FunctionCallback.builder()
						.function("turnLight", (LightInfo lightInfo) -> {
							logger.info("Turning light to [" + lightInfo.isOn + "] in " + lightInfo.roomName());
							state.put(lightInfo.roomName(), lightInfo.isOn());
						})
						.description("Turn light on or off in a room")
						.inputType(LightInfo.class)
						.build())
					.call().content();
			// @formatter:on
			logger.info("Response: " + content);
			assertThat(state).containsEntry("kitchen", Boolean.TRUE);
			assertThat(state).containsEntry("living room", Boolean.TRUE);
		});
	}

	@Test
	void functionCallTest2() {
		this.contextRunner.withPropertyValues("spring.ai.openai.chat.options.model=" + ChatModel.GPT_4_O_MINI.getName())
			.run(context -> {

				OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);

			// @formatter:off
			String content = ChatClient.builder(chatModel).build().prompt()
					.user("What's the weather like in Amsterdam?")
					.functions(FunctionCallback.builder()
						.function("CurrentWeatherService", input -> "18 degrees Celsius")
						.description("Get the weather in location")
						.inputType(MockWeatherService.Request.class)
					.build())
					.call().content();
			// @formatter:on
				logger.info("Response: " + content);

				assertThat(content).contains("18");
			});
	}

	@Test
	void streamingFunctionCallTest() {

		this.contextRunner.withPropertyValues("spring.ai.openai.chat.options.model=" + ChatModel.GPT_4_O_MINI.getName())
			.run(context -> {

				OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);

			// @formatter:off
			String content = ChatClient.builder(chatModel).build().prompt()
					.user("What's the weather like in San Francisco, Tokyo, and Paris?")
					.functions(FunctionCallback.builder()
						.function("CurrentWeatherService", new MockWeatherService())
						.description("Get the weather in location")
						.inputType(MockWeatherService.Request.class)
						.build())
					.stream().content()
					.collectList().block().stream().collect(Collectors.joining());
			// @formatter:on

				logger.info("Response: " + content);

				assertThat(content).contains("30", "10", "15");
			});
	}

}
