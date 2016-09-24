/**
 * Copyright (c) <2016> <hasder>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 	
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 * 
*/

package com.eislab.af.translator.spokes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.protocol.RequestAcceptEncoding;
import org.apache.http.client.protocol.ResponseContentEncoding;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.DefaultHttpServerIODispatch;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerRegistry;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.ListeningIOReactor;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.EntityUtils;
import org.eclipse.californium.core.network.config.NetworkConfig;

import com.eislab.af.translator.data.BaseContext;
import com.google.common.net.InetAddresses;

public class HttpServer_spoke implements BaseSpokeProvider {

	BaseSpoke nextSpoke;
	Map<Integer,HttpAsyncExchange> cachedHttpExchangeMap = new HashMap<Integer,HttpAsyncExchange>();
	private String address = "";
	ListeningIOReactor ioReactor;

	@Override
	public void close() {
		try {
			ioReactor.shutdown();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	@Override
	public void in(BaseContext context) {
		// TODO Auto-generated method stub
		
		// get the sample http response
		HttpResponse httpResponse = cachedHttpExchangeMap.get(context.getKey()).getResponse();
		
		
		try {
			constructHttpResponse(context.getContent(), httpResponse);
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		long lStartTime = System.nanoTime();
		System.out.println(lStartTime + ": http: response sent");
		// send the response
		cachedHttpExchangeMap.get(context.getKey()).submitResponse();
	
		cachedHttpExchangeMap.remove(context.getKey());
	}

	@Override
	public void setNextSpoke(Object nextSpoke) {
		this.nextSpoke = (BaseSpoke)nextSpoke;
	}

	@Override
	public String getAddress() {
		return "http://" + address;
	}

	
	private static void constructHttpResponse(String msg, HttpResponse httpResponse) throws UnsupportedEncodingException {
		
		if(msg != null) {
			HttpEntity httpEntity;
			// create the entity
			httpEntity = new StringEntity(msg);
			httpResponse.setEntity(httpEntity);
		}
	}
	
	
	private static final Charset UTF_8 = Charset.forName("UTF-8");
	private static final int SOCKET_TIMEOUT = NetworkConfig.getStandard().getInt(NetworkConfig.Keys.HTTP_SERVER_SOCKET_TIMEOUT);
	private static final int SOCKET_BUFFER_SIZE = NetworkConfig.getStandard().getInt(NetworkConfig.Keys.HTTP_SERVER_SOCKET_BUFFER_SIZE);
//	private static final int GATEWAY_TIMEOUT = SOCKET_TIMEOUT * 3 / 4;
	private static final String SERVER_NAME = "Californium Http Proxy";
	
	
	
	public HttpServer_spoke(String ipaddress, String path) throws IOException, InterruptedException {

		address = ipaddress;
		// HTTP parameters for the server
		HttpParams params = new SyncBasicHttpParams();
		params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, SOCKET_TIMEOUT).setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, SOCKET_BUFFER_SIZE).setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true).setParameter(CoreProtocolPNames.ORIGIN_SERVER, SERVER_NAME);

		// Create HTTP protocol processing chain
		// Use standard server-side protocol interceptors
		HttpRequestInterceptor[] requestInterceptors = new HttpRequestInterceptor[] { new RequestAcceptEncoding() };
		HttpResponseInterceptor[] responseInterceptors = new HttpResponseInterceptor[] { new ResponseContentEncoding(), new ResponseDate(), new ResponseServer(), new ResponseContent(), new ResponseConnControl() };
		HttpProcessor httpProcessor = new ImmutableHttpProcessor(requestInterceptors, responseInterceptors);

		// Create request handler registry
		HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();

		// register the handler that will reply to the proxy requests

		registry.register(path, new RequestHandler("", true));

		// Create server-side HTTP protocol handler
		HttpAsyncService protocolHandler = new HttpAsyncService(httpProcessor, new DefaultConnectionReuseStrategy(), registry, params);

		// Create HTTP connection factory
		NHttpConnectionFactory<DefaultNHttpServerConnection> connFactory = new DefaultNHttpServerConnectionFactory(params);

		// Create server-side I/O event dispatch
		final IOEventDispatch ioEventDispatch = new DefaultHttpServerIODispatch(protocolHandler, connFactory);
		
		

		

		try {
			// Create server-side I/O reactor
			ioReactor = new DefaultListeningIOReactor();
			// Listen of the given port
			
			
			InetSocketAddress socketAddress = new InetSocketAddress(ipaddress, 0);
			ListenerEndpoint endpoint1 = ioReactor.listen(socketAddress);
			        
	        
			// create the listener thread
			Thread listener = new Thread("Http listener") {

				@Override
				public void run() {
					try {
						ioReactor.execute(ioEventDispatch);
						
					} catch (IOException e) {
//							LOGGER.severe("I/O Exception: " + e.getMessage());
					}

				}
			};

			listener.setDaemon(false);
			listener.start();
			

			endpoint1.waitFor();

			address = address + ":" + Integer.toString(((InetSocketAddress) endpoint1.getAddress()).getPort()) + "/";
			System.out.println(address);
			
			
		} catch (IOException e) {
//				LOGGER.severe("I/O error: " + e.getMessage());
		}
	}
	
	/**
	 * Class associated with the http service to translate the http requests
	 * in coap requests and to produce the http responses. Even if the class
	 * accepts a string indicating the name of the proxy resource, it is
	 * still thread-safe because the local resource is set in the
	 * constructor and then only read by the methods.
	 */
	private class RequestHandler implements HttpAsyncRequestHandler<HttpRequest> {

//		private final String localResource;
//		private final boolean proxyingEnabled;

		/**
		 * Instantiates a new proxy request handler.
		 * 
		 * @param localResource
		 *            the local resource
		 * @param proxyingEnabled
		 */
		public RequestHandler(String localResource, boolean proxyingEnabled) {
			super();

//			this.localResource = localResource;
//			this.proxyingEnabled = proxyingEnabled;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * org.apache.http.nio.protocol.HttpAsyncRequestHandler#handle(java.
		 * lang.Object, org.apache.http.nio.protocol.HttpAsyncExchange,
		 * org.apache.http.protocol.HttpContext)
		 */
		@Override
		public void handle(HttpRequest httpRequest, HttpAsyncExchange httpExchange, HttpContext httpContext) throws HttpException, IOException {
//				if (Bench_Help.DO_LOG) 
//					LOGGER.finer("Incoming http request: " + httpRequest.getRequestLine());

//			try {
				long lStartTime = System.nanoTime();
				System.out.println(lStartTime + ": http: request received");
				activity++;
				BaseContext context = new BaseContext();
				
				//store the http context for generating the response
				cachedHttpExchangeMap.put(context.getKey(), httpExchange);

				//TODO: prepare http request for next spoke
				context.setMethod(httpRequest.getRequestLine().getMethod().toLowerCase());
				context.setPath(httpRequest.getRequestLine().getUri());
				// set the payload if the http entity is present
				if (httpRequest instanceof HttpEntityEnclosingRequest) {
					HttpEntity httpEntity = ((HttpEntityEnclosingRequest) httpRequest).getEntity();

					
					
					// get the bytes from the entity
					String payload = EntityUtils.toString(httpEntity);
					if (payload != null && payload.length() > 0) {

						// the only supported charset in CoAP is UTF-8
						Charset coapCharset = UTF_8;

						// get the charset for the http entity
						ContentType httpContentType = ContentType.getOrDefault(httpEntity);
						Charset httpCharset = httpContentType.getCharset();

						// check if the charset is the one allowed by coap
						if (httpCharset != null && !httpCharset.equals(coapCharset)) {
							// translate the payload to the utf-8 charset
//							payload = changeCharset(payload, httpCharset, coapCharset);
						}
					}

					context.setContent(payload);
				}
				nextSpoke.in(context);

		}

		/*
		 * (non-Javadoc)
		 * @see
		 * org.apache.http.nio.protocol.HttpAsyncRequestHandler#processRequest
		 * (org.apache.http.HttpRequest,
		 * org.apache.http.protocol.HttpContext)
		 */
		@Override
		public HttpAsyncRequestConsumer<HttpRequest> processRequest(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
			// Buffer request content in memory for simplicity
			return new BasicAsyncRequestConsumer();
		}
	}

	private int activity = 0;
	@Override
	public int getLastActivity() {
		// TODO Auto-generated method stub
		return activity;
	}

	@Override
	public void clearActivity() {
		// TODO Auto-generated method stub
		activity = 0;
	}
	


}
