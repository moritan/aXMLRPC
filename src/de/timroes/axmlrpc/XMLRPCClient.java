package de.timroes.axmlrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.conn.ssl.SSLSocketFactory;

import de.timroes.axmlrpc.serializer.SerializerHandler;
import de.timroes.axmlrpc.utils.CustomSSLSocketFactory;

/**
 * An XMLRPCClient is a client used to make XML-RPC (Extensible Markup Language
 * Remote Procedure Calls).
 * The specification of XMLRPC can be found at http://www.xmlrpc.com/spec.
 * You can use flags to extend the functionality of the client to some extras.
 * Further information on the flags can be found in the documentation of these.
 * For a documentation on how to use this class see also the readme file delivered
 * with the source of this library.
 *
 * @author Tim Roes
 */
public class XMLRPCClient {

	private static final String DEFAULT_USER_AGENT = "aXMLRPC";

	/**
	 * Constants from the http protocol.
	 */
	static final String USER_AGENT = "User-Agent";
	static final String CONTENT_TYPE = "Content-Type";
	static final String TYPE_XML = "text/xml";
	static final String HOST = "Host";
	static final String CONTENT_LENGTH = "Content-Length";
	static final String HTTP_POST = "POST";

	/**
	 * XML elements to be used.
	 */
	static final String METHOD_RESPONSE = "methodResponse";
	static final String PARAMS = "params";
	static final String PARAM = "param";
	static final String VALUE = "value";
	static final String FAULT = "fault";
	static final String METHOD_CALL = "methodCall";
	static final String METHOD_NAME = "methodName";
	static final String STRUCT_MEMBER = "member";

	/**
	 * No flags should be set.
	 */
	public static final int FLAGS_NONE = 0x0;

	/**
	 * The client should parse responses strict to specification.
	 * It will check if the given content-type is right.
	 * The method name in a call must only contain of A-Z, a-z, 0-9, _, ., :, /
	 * Normally this is not needed.
	 */
	public static final int FLAGS_STRICT = 0x01;

	/**
	 * The client will be able to handle 8 byte integer values (longs).
	 * The xml type tag &lt;i8&gt; will be used. This is not in the specification
	 * but some libraries and servers support this behaviour.
	 * If this isn't enabled you cannot recieve 8 byte integers and if you try to
	 * send a long the value must be within the 4byte integer range.
	 */
	public static final int FLAGS_8BYTE_INT = 0x02;

	/**
	 * With this flag, the client will be able to handle cookies, meaning saving cookies
	 * from the server and sending it with every other request again. This is needed
	 * for some XML-RPC interfaces that support login.
	 */
	public static final int FLAGS_ENABLE_COOKIES = 0x04;

	/**
	 * The client will be able to send null values. A null value will be send
	 * as <nil/>. This extension is described under: http://ontosys.com/xml-rpc/extensions.php
	 */
	public static final int FLAGS_NIL = 0x08;

	/**
	 * With this flag enabled, the XML-RPC client will ignore the HTTP status
	 * code of the response from the server. According to specification the
	 * status code must be 200. This flag is only needed for the use with 
	 * not standard compliant servers.
	 */
	public static final int FLAGS_IGNORE_STATUSCODE = 0x10;

	private int flags;

	private URL url;
	private CustomSSLSocketFactory sf;
	private Map<String,String> httpParameters = new HashMap<String, String>();

	private Map<Long,Caller> backgroundCalls = new HashMap<Long, Caller>();

	private ResponseParser responseParser;
	private CookieManager cookieManager;
	private AuthenticationManager authManager;

	/**
	 * Create a new XMLRPC client for the given url.
	 *
	 * @param url The url to send the requests to.
	 * @param userAgent A user agent string to use in the http requests.
	 * @param flags A combination of flags to be set.
	 */
	public XMLRPCClient(URL url, String userAgent, int flags) {

		SerializerHandler.initialize(flags);

		this.url = url;

		this.flags = flags;
		// Create a parser for the http responses.
		responseParser = new ResponseParser();

		cookieManager = new CookieManager(flags);
		authManager = new AuthenticationManager();

		httpParameters.put(CONTENT_TYPE, TYPE_XML);
		httpParameters.put(USER_AGENT, userAgent);
		
		sf = new CustomSSLSocketFactory();
	}

	/**
	 * Create a new XMLRPC client for the given url.
	 * The default user agent string will be used.
	 *
	 * @param url The url to send the requests to.
	 * @param flags A combination of flags to be set.
	 */
	public XMLRPCClient(URL url, int flags) {
		this(url, DEFAULT_USER_AGENT, flags);
	}

	/**
	 * Create a new XMLRPC client for the given url.
	 * No flags will be set.
	 *
	 * @param url The url to send the requests to.
	 * @param userAgent A user agent string to use in the http request.
	 */
	public XMLRPCClient(URL url, String userAgent) {
		this(url, userAgent, FLAGS_NONE);
	}

	/**
	 * Create a new XMLRPC client for the given url.
	 * No flags will be used.
	 * The default user agent string will be used.
	 *
	 * @param url The url to send the requests to.
	 */
	public XMLRPCClient(URL url) {
		this(url, DEFAULT_USER_AGENT, FLAGS_NONE);
	}

	/**
	 * Sets the user agent string.
	 * If this method is never called the default
	 * user agent 'aXMLRPC' will be used.
	 *
	 * @param userAgent The new user agent string.
	 */
	public void setUserAgentString(String userAgent) {
		httpParameters.put(USER_AGENT, userAgent);
	}

	/**
	 * Set a http header field to a custom value.
	 * You cannot modify the Host or Content-Type field that way.
	 * If the field already exists, the old value is overwritten.
	 *
	 * @param headerName The name of the header field.
	 * @param headerValue The new value of the header field.
	 */
	public void setCustomHttpHeader(String headerName, String headerValue) {
		if(CONTENT_TYPE.equals(headerName) || HOST.equals(headerName)
				|| CONTENT_LENGTH.equals(headerName)) {
			throw new XMLRPCRuntimeException("You cannot modify the Host, Content-Type or Content-Length header.");
		}
		httpParameters.put(headerName, headerValue);
	}

	/**
	 * Set the username and password that should be used to perform basic
	 * http authentication.
	 * 
	 * @param user Username
	 * @param pass Password
	 */
	public void setLoginData(String user, String pass) {
		authManager.setAuthData(user, pass);
	}
	
	/**
	 * Delete all cookies currently used by the client.
	 * This method has only an effect, as long as the FLAGS_ENABLE_COOKIES has
	 * been set on this client.
	 */
	public void clearCookies() {
		cookieManager.clearCookies();
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. If the method requires parameters, this must be set.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param method A method name to call.
	 * @param params An array of parameters for the method.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String method, Object[] params) throws XMLRPCException {
		return new Caller().call(method, params);
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. This method is only for methods that doesn't require any parameters.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param methodName A method name to call.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String methodName) throws XMLRPCException {
		return call(methodName, null);
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. If the method requires parameters, this must be set.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param methodName A method name to call.
	 * @param param1 The first parameter of the method.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String methodName, Object param1) throws XMLRPCException {
		return call(methodName, new Object[]{param1});
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. If the method requires parameters, this must be set.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param methodName A method name to call.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String methodName, Object param1, Object param2) throws XMLRPCException {
		return call(methodName, new Object[]{param1,param2});
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. If the method requires parameters, this must be set.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param methodName A method name to call.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @param param3 The third parameter of the method.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String methodName, Object param1, Object param2, Object param3)
			throws XMLRPCException {
		return call(methodName, new Object[]{param1,param2,param3});
	}

	/**
	 * Call a remote procedure on the server. The method must be described by
	 * a method name. If the method requires parameters, this must be set.
	 * The type of the return object depends on the server. You should consult
	 * the server documentation and then cast the return value according to that.
	 * This method will block until the server returned a result (or an error occured).
	 * Read the readme file delivered with the source code of this library for more
	 * information.
	 *
	 * @param methodName A method name to call.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @param param3 The third parameter of the method.
	 * @param param4 The fourth parameter of the method.
	 * @return The result of the server.
	 * @throws XMLRPCException Will be thrown if an error occured during the call.
	 */
	public Object call(String methodName, Object param1, Object param2, Object param3,
			Object param4) throws XMLRPCException {
		return call(methodName, new Object[]{param1,param2,param3,param4});
	}


	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. If the method requires parameters, this must
	 * be set. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @param params An array of parameters for the method.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName, Object[] params) {
		long id = System.currentTimeMillis();
		new Caller(listener, id, methodName, params).start();
		return id;
	}

	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. This call is only for methods that doesn't require
	 * any parameters. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName) {
		return callAsync(listener, methodName, null);
	}

	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. If the method requires parameters, this must
	 * be set. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @param param1 The first parameter of the method.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName, Object param1) {
		return callAsync(listener, methodName, new Object[]{param1});
	}

	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. If the method requires parameters, this must
	 * be set. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName, Object param1,
			Object param2) {
		return callAsync(listener, methodName, new Object[]{param1,param2});
	}

	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. If the method requires parameters, this must
	 * be set. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @param param3 The third parameter of the method.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName, Object param1,
			Object param2, Object param3) {
		return callAsync(listener, methodName, new Object[]{param1,param2,param3});
	}

	/**
	 * Asynchronously call a remote procedure on the server. The method must be
	 * described by a method  name. If the method requires parameters, this must
	 * be set. When the server returns a response the onResponse method is called
	 * on the listener. If the server returns an error the onServerError method
	 * is called on the listener. The onError method is called whenever something
	 * fails. This method returns immediately and returns an identifier for the
	 * request. All listener methods get this id as a parameter to distinguish between
	 * multiple requests.
	 *
	 * @param listener A listener, which will be notified about the server response or errors.
	 * @param methodName A method name to call on the server.
	 * @param param1 The first parameter of the method.
	 * @param param2 The second parameter of the method.
	 * @param param3 The third parameter of the method.
	 * @param param4 The fourth parameter of the method.
	 * @return The id of the current request.
	 */
	public long callAsync(XMLRPCCallback listener, String methodName, Object param1,
			Object param2, Object param3, Object param4) {
		return callAsync(listener, methodName, new Object[]{param1,param2,param3,param4});
	}

	/**
	 * Cancel a specific asynchron call.
	 * 
	 * @param id The id of the call as returned by the callAsync method.
	 */
	public void cancel(long id) {

		// Lookup the background call for the given id.
		Caller cancel = backgroundCalls.get(id);
		if(cancel == null) {
			return;
		}

		// Cancel the thread
		cancel.cancel();
		
		try {
			// Wait for the thread
			cancel.join();
		} catch (InterruptedException ex) {
			// Ignore this
		}

	}

	/**
	 * Create a call object from a given method string and parameters.
	 *
	 * @param method The method that should be called.
	 * @param params An array of parameters or null if no parameters needed.
	 * @return A call object.
	 */
	private Call createCall(String method, Object[] params) {

		if(isFlagSet(FLAGS_STRICT) && !method.matches("^[A-Za-z0-9\\._:/]*$")) {
			throw new XMLRPCRuntimeException("Method name must only contain A-Z a-z . : _ / ");
		}

		return new Call(method, params);

	}

	/**
	 * Checks whether a specific flag has been set.
	 *
	 * @param flag The flag to check for.
	 * @return Whether the flag has been set.
	 */
	private boolean isFlagSet(int flag) {
		return (this.flags & flag) != 0;
	}

	/**
	 * The Caller class is used to make asynchronous calls to the server.
	 * For synchronous calls the Thread function of this class isn't used.
	 */
	private class Caller extends Thread {

		private XMLRPCCallback listener;
		private long threadId;
		private String methodName;
		private Object[] params;

		private volatile boolean canceled;
		private HttpURLConnection http;

		/**
		 * Create a new Caller for asynchronous use.
		 *
		 * @param listener The listener to notice about the response or an error.
		 * @param threadId An id that will be send to the listener.
		 * @param methodName The method name to call.
		 * @param params The parameters of the call or null.
		 */
		public Caller(XMLRPCCallback listener, long threadId, String methodName, Object[] params) {
			this.listener = listener;
			this.threadId = threadId;
			this.methodName = methodName;
			this.params = params;
		}

		/**
		 * Create a new Caller for synchronous use.
		 * If the caller has been created with this constructor you cannot use the
		 * start method to start it as a thread. But you can call the call method
		 * on it for synchronous use.
		 */
		public Caller() { }

		/**
		 * The run method is invoked when the thread gets started.
		 * This will only work, if the Caller has been created with parameters.
		 * It execute the call method and notify the listener about the result.
		 */
		@Override
		public void run() {

			if(listener == null)
				return;

			try {
				backgroundCalls.put(threadId, this);
				Object o = this.call(methodName, params);
				listener.onResponse(threadId, o);
			} catch(CancelException ex) {
				// Don't notify the listener, if the call has been canceled.
			} catch(XMLRPCServerException ex) {
				listener.onServerError(threadId, ex);
			} catch (XMLRPCException ex) {
				listener.onError(threadId, ex);
			} finally {
				backgroundCalls.remove(threadId);
			}

		}

		/**
		 * Cancel this call. This will abort the network communication.
		 */
		public void cancel() {
			// Set the flag, that this thread has been canceled
			canceled = true;
			// Disconnect the connection to the server
			http.disconnect();
		}

		/**
		 * Call a remote procedure on the server. The method must be described by
		 * a method name. If the method requires parameters, this must be set.
		 * The type of the return object depends on the server. You should consult
		 * the server documentation and then cast the return value according to that.
		 * This method will block until the server returned a result (or an error occured).
		 * Read the readme file delivered with the source code of this library for more
		 * information.
		 *
		 * @param method A method name to call.
		 * @param params An array of parameters for the method.
		 * @return The result of the server.
		 * @throws XMLRPCException Will be thrown if an error occured during the call.
		 */
		public Object call(String methodName, Object[] params) throws XMLRPCException {
			
			try {

				Call c = createCall(methodName, params);

				URLConnection conn = url.openConnection();
				if (url.getProtocol().equals("https"))
				{
					http = (HttpsURLConnection)conn;
					((HttpsURLConnection)http).setSSLSocketFactory(sf);
					((HttpsURLConnection)http).setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				}else if (url.getProtocol().equals("http"))
				{
					http = (HttpURLConnection)conn;
				}else
				{
					throw new IllegalArgumentException("The URL is not for a http connection.");
				}

				http.setRequestMethod(HTTP_POST);
				http.setDoOutput(true);
				http.setDoInput(true);

				// Set the request parameters
				for(Map.Entry<String,String> param : httpParameters.entrySet()) {
					http.setRequestProperty(param.getKey(), param.getValue());
				}

				authManager.setAuthentication(http);
				cookieManager.setCookies(http);

				OutputStreamWriter stream = new OutputStreamWriter(http.getOutputStream());
				stream.write(c.getXML());
				stream.flush();
				stream.close();

				InputStream istream = http.getInputStream();

				if(!isFlagSet(FLAGS_IGNORE_STATUSCODE)
					&& http.getResponseCode() != HttpURLConnection.HTTP_OK) {
					throw new XMLRPCException("The status code of the http response must be 200.");
				}

				// Check for strict parameters
				if(isFlagSet(FLAGS_STRICT)) {
					if(!http.getContentType().startsWith(TYPE_XML)) {
						throw new XMLRPCException("The Content-Type of the response must be text/xml.");
					}
				}

				cookieManager.readCookies(http);

				return responseParser.parse(istream);

			} catch(SocketException ex) {
				// If the thread has been canceled this exception will be thrown.
				// So only throw an exception if the thread hasnt been canceled
				// or if the thred has not been started in background.
				if(!canceled || threadId <= 0) {
					throw new XMLRPCException(ex);
				} else {
					throw new CancelException();
				}
			} catch (IOException ex) {
				throw new XMLRPCException(ex);
			} 

		}

	}

	private class CancelException extends RuntimeException { }

}