package com.mannschaft.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-1335: JVM 既定 TZ が JST に確定しても、prod の JSON ログの {@code @timestamp} は
 * 従来どおり UTC 表記であること（{@code LogstashEncoder} に {@code <timeZone>UTC</timeZone>} を明示している）。
 */
@DisplayName("prod の LogstashEncoder は @timestamp を UTC 表記に固定する")
class LogbackProdTimeZoneGuardTest {

    @Test
    @DisplayName("logback-spring.xml の LogstashEncoder に timeZone=UTC がある")
    void LogstashEncoderはUTC固定() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml").isNotNull();
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList encoders = doc.getElementsByTagName("encoder");
            int checked = 0;
            for (int i = 0; i < encoders.getLength(); i++) {
                Element encoder = (Element) encoders.item(i);
                if (!encoder.getAttribute("class").endsWith("LogstashEncoder")) {
                    continue;
                }
                checked++;
                NodeList tz = encoder.getElementsByTagName("timeZone");
                assertThat(tz.getLength()).as("LogstashEncoder の timeZone 指定").isEqualTo(1);
                assertThat(tz.item(0).getTextContent().trim()).isEqualTo("UTC");
            }
            assertThat(checked).as("LogstashEncoder の個数").isGreaterThanOrEqualTo(1);
        }
    }
}
