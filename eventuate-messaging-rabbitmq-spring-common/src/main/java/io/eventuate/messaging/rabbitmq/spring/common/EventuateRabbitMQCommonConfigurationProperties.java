package io.eventuate.messaging.rabbitmq.spring.common;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;

import com.rabbitmq.client.ConnectionFactory;

public class EventuateRabbitMQCommonConfigurationProperties {
  
	@Value("${rabbitmq.broker.host:#{null}}")
	private String brokerHost;
	
	@Value("${rabbitmq.broker.port:#{null}}")
	private Integer brokerPort;
	
	@Value("${rabbitmq.broker.username:#{null}}")
	private String brokerUsername;
	
	@Value("${rabbitmq.broker.password:#{null}}")
	private String brokerPassword;
	
	@Value("${rabbitmq.broker.virtual-host:#{null}}")
	private String brokerVirtualHost;
	
	@Value("${rabbitmq.broker.ssl.enabled:true}")
	private boolean brokerSslEnabled;
	
	@Value("${rabbitmq.broker.ssl.algorithm:TLSv1.2}")
	private String brokerSslAlgorithm;

	public String getBrokerHost() {
		return brokerHost;
	}

	public int getBrokerPort() {
		return brokerPort;
	}

	public String getBrokerUsername() {
		return brokerUsername;
	}

	public String getBrokerPassword() {
		return brokerPassword;
	}

	public String getBrokerVirtualHost() {
		return brokerVirtualHost;
	}

	public boolean isBrokerSslEnabled() {
		return brokerSslEnabled;
	}

	public String getBrokerSslAlgorithm() {
		return brokerSslAlgorithm;
	}	
	
	public ConnectionFactory createConnectionFactory() throws KeyManagementException, NoSuchAlgorithmException {
		validateConfig();
		
		ConnectionFactory factory = new ConnectionFactory();
		
		factory.setHost(brokerHost);
		factory.setPort(brokerPort);
        factory.setUsername(brokerUsername);
        factory.setPassword(brokerPassword);
        factory.setVirtualHost(brokerVirtualHost);
        
        if(brokerSslEnabled) {
        	factory.useSslProtocol(brokerSslAlgorithm);
        }
        
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(4000);
        factory.setConnectionTimeout(10000);
        
        return factory;
	}
	
	private void validateConfig() {
		if(brokerHost == null) {
			throw new IllegalArgumentException("rabbitmq.broker.host should be specified");
		}
		
		if(brokerPort == null) {
			throw new IllegalArgumentException("rabbitmq.broker.port should be specified");
		}
		
		if(brokerUsername == null) {
			throw new IllegalArgumentException("rabbitmq.broker.username should be specified");
		}
		
		if(brokerPassword == null) {
			throw new IllegalArgumentException("rabbitmq.broker.password should be specified");
		}
		
		if(brokerVirtualHost == null) {
			throw new IllegalArgumentException("rabbitmq.broker.virtual-host should be specified");
		}
	}
}
