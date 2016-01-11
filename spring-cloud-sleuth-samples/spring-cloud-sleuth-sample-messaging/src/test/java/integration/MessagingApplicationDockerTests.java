/*
 * Copyright 2013-2015 the original author or authors.
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
package integration;

import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Span;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.WebIntegrationTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.util.JdkIdGenerator;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.DockerComposeContainer;
import sample.SampleMessagingApplication;
import tools.AbstractIntegrationTest;
import tools.IntegrationTestSpanCollector;
import tools.RequestSendingRunnable;

import java.io.File;
import java.util.Collection;

import static org.assertj.core.api.BDDAssertions.then;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = { AbstractIntegrationTest.Config.class, SampleMessagingApplication.class })
@WebIntegrationTest
@TestPropertySource(properties="sample.zipkin.enabled=true")
@Slf4j
public class MessagingApplicationDockerTests extends AbstractIntegrationTest {

	private static int port = 3381;
	private static String sampleAppUrl = "http://localhost:" + port;
	@Autowired IntegrationTestSpanCollector integrationTestSpanCollector;

	@ClassRule
	public static DockerComposeContainer environment =
			new DockerComposeContainer(new File("src/test/resources/docker-compose.yml"))
					.withExposedService("rabbitmq_1", 5672);

	@After
	public void cleanup() {
		integrationTestSpanCollector.hashedSpans.clear();
	}

	@Test
	public void should_propagate_spans_for_messaging() {
		String traceId = new JdkIdGenerator().generateId().toString();

		await().until(httpMessageWithTraceIdInHeadersIsSuccessfullySent(sampleAppUrl + "/", traceId));

		await().until(() -> {
			thenAllSpansHaveTraceIdEqualTo(traceId);
		});
	}

	@Test
	public void should_propagate_spans_for_messaging_with_async() {
		String traceId = new JdkIdGenerator().generateId().toString();

		await().until(httpMessageWithTraceIdInHeadersIsSuccessfullySent(sampleAppUrl + "/xform", traceId));

		await().until(() -> {
			thenAllSpansHaveTraceIdEqualTo(traceId);
			thenThereIsAtLeastOneBinaryAnnotationWithKey("background-sleep-millis");
		});
	}

	private void thenThereIsAtLeastOneBinaryAnnotationWithKey(String binaryAnnotationKey) {
		then(integrationTestSpanCollector.hashedSpans.stream()
				.filter(Span::isSetBinary_annotations)
				.map(Span::getBinary_annotations)
				.flatMap(Collection::stream)
				.filter(binaryAnnotation -> StringUtils.hasText(binaryAnnotation.getKey()))
				.map(BinaryAnnotation::getKey)
				.anyMatch(binaryAnnotationKey::equals)).isTrue();
	}

	private RequestSendingRunnable httpMessageWithTraceIdInHeadersIsSuccessfullySent(String endpoint, String traceId) {
		return new RequestSendingRunnable(restTemplate, endpoint, traceId);
	}

	private void thenAllSpansHaveTraceIdEqualTo(String traceId) {
		then(integrationTestSpanCollector.hashedSpans.stream().allMatch(span -> span.getTrace_id() == zipkinHashedTraceId(traceId))).isTrue();
	}

}
