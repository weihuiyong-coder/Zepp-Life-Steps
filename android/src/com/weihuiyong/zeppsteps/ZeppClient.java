package com.weihuiyong.zeppsteps;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Native port of this repository's login and step-upload protocol; no intermediary server. */
final class ZeppClient {
    static final String APP = "com.huami.midong";
    static final String DN = "api-mifit.zepp.com,api-user.zepp.com,api-mifit.zepp.com,api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com";
    interface Progress { void update(String value); }
    interface Transport { Response execute(String method, String url, Map<String,String> headers, Map<String,String> form) throws Exception; void cancel(); }
    static final class Response {
        final int status; final String body; final Map<String,List<String>> headers;
        Response(int status, String body, Map<String,List<String>> headers) { this.status=status; this.body=body; this.headers=headers; }
        String header(String name) {
            if (headers != null) for (Map.Entry<String,List<String>> entry : headers.entrySet())
                if (entry.getKey()!=null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
            return "";
        }
    }
    static final class Session {
        final String userId; final String appToken;
        Session(String userId, String appToken) { this.userId=userId; this.appToken=appToken; }
    }
    static final class ApiException extends Exception {
        final String stage; final String code; final int retryAfter;
        ApiException(String stage, String code, String message) { this(stage,code,message,0); }
        ApiException(String stage, String code, String message, int retryAfter) { super(message); this.stage=stage; this.code=code; this.retryAfter=retryAfter; }
    }
    private final Transport transport;
    ZeppClient() { this(new HttpTransport()); }
    ZeppClient(Transport transport) { this.transport=transport; }
    void cancel() { transport.cancel(); }

    static String normalizeAccount(String value) throws ApiException {
        String account = value == null ? "" : value.trim();
        if (account.length()>254 || !(account.matches("\\+?[0-9]+") || account.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")))
            throw new ApiException("输入", "INVALID_ACCOUNT", "请输入 Zepp Life 的手机号或邮箱，不是微信号。");
        return account.matches("[0-9]+") ? "+86"+account : account;
    }
    static int validateSteps(String text) throws ApiException {
        if (text==null || !text.matches("[0-9]{1,6}")) throw new ApiException("输入","INVALID_STEPS","步数必须是 0 到 100000 的整数。");
        int steps=Integer.parseInt(text);
        if (steps>100000) throw new ApiException("输入","INVALID_STEPS","步数必须是 0 到 100000 的整数。");
        return steps;
    }
    static String encode(String value) { try { return URLEncoder.encode(value,"UTF-8"); } catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); } }
    static String form(Map<String,String> map) {
        StringBuilder out=new StringBuilder();
        for(Map.Entry<String,String> e:map.entrySet()) { if(out.length()>0)out.append('&'); out.append(encode(e.getKey())).append('=').append(encode(e.getValue())); }
        return out.toString();
    }
    static Map<String,String> map(String... pairs) {
        Map<String,String> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)out.put(pairs[i],pairs[i+1]);
        return out;
    }
    private static String queryValue(String location, String key) {
        int q=location.indexOf('?'); String query=q>=0?location.substring(q+1):location;
        int fragment=query.indexOf('#'); if(fragment>=0) query=query.substring(0,fragment);
        for(String part:query.split("&")) {
            String[] pair=part.split("=",2);
            try { if(pair.length==2 && URLDecoder.decode(pair[0],"UTF-8").equals(key)) return URLDecoder.decode(pair[1],"UTF-8"); }
            catch(Exception ignored) { return ""; }
        }
        return "";
    }
    private static String field(JSONObject object,String name) {
        if(object==null || object.isNull(name))return "";
        return object.optString(name,"");
    }
    private static JSONObject json(String body,String stage) throws ApiException {
        try { return new JSONObject(body); }
        catch(Exception ignored) { throw new ApiException(stage,"INVALID_RESPONSE","接口返回了无法识别的数据；请稍后再试，或检查接口是否变化。"); }
    }
    private static Map<String,String> commonHeaders() {
        return map("app_name",APP,"hm-privacy-ceip","false","x-request-id",UUID.randomUUID().toString(),"accept-language","zh-CN",
                "appname",APP,"cv","151689_9.12.5","v","2.0","appplatform","android_phone","vb","202509151347","vn","9.12.5",
                "User-Agent","Zepp/9.12.5 (2206122SC; Android 14; Density/2.625)","Accept-Encoding","gzip");
    }
    private Response request(String stage,String method,String url,Map<String,String> headers,Map<String,String> data) throws ApiException {
        Response response;
        try { response=transport.execute(method,url,headers,data); }
        catch(ApiException e){throw e;}
        catch(SocketTimeoutException e){throw new ApiException(stage,"TIMEOUT","请求超时，请检查网络后再试。未自动重试。");}
        catch(UnknownHostException e){throw new ApiException(stage,"DNS_ERROR","无法解析服务地址，请检查手机网络或 DNS 设置。");}
        catch(SSLException e){throw new ApiException(stage,"TLS_ERROR","安全连接失败。请检查系统时间、网络代理或证书设置。");}
        catch(InterruptedIOException e){throw new ApiException(stage,"CANCELLED","操作已取消；已经发出的提交可能仍会被服务器处理。");}
        catch(Exception e){throw new ApiException(stage,"NETWORK_ERROR","网络连接失败。没有自动重试；请先确认网络状态。");}
        if(response.status==429) throw new ApiException(stage,"RATE_LIMITED","接口限制了请求频率，请按提示稍后再试。",retryAfter(response.header("Retry-After")));
        if(response.status==401 || response.status==403 || "401".equals(queryValue(response.header("Location"),"error")))
            throw new ApiException(stage,"LOGIN_REJECTED","登录被拒绝。请检查 Zepp Life 账号密码；也可能需要在官方应用完成验证。");
        if(response.status>=400)throw new ApiException(stage,"HTTP_"+response.status,"服务返回 HTTP "+response.status+"。请稍后再试；不要连续重复提交。");
        return response;
    }
    static int retryAfter(String header) {
        try { return (int)Math.max(1,Math.min(86400,Long.parseLong(header.trim()))); }
        catch(Exception ignored) {
            try { SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US); f.setLenient(false); return (int)Math.max(1,Math.min(86400,(f.parse(header).getTime()-System.currentTimeMillis()+999)/1000)); }
            catch(Exception ignoredAgain){return 60;}
        }
    }
    Session login(String input,String password,Progress progress) throws ApiException {
        String account=normalizeAccount(input);
        if(password==null || password.isEmpty() || password.length()>256)throw new ApiException("输入","INVALID_PASSWORD","请输入密码，长度不超过 256 个字符。");
        progress.update("1 / 3  正在验证账号");
        Map<String,String> registrationHeaders=map("x-request-id",UUID.randomUUID().toString(),"app_name","com.huami.webapp","lang","zh",
                "accept","application/json, text/plain, */*","accept-language","zh-CN,zh;q=0.9","User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
                "origin","https://user.zepp.com","referer","https://user.zepp.com/","dnt","1","Accept-Encoding","gzip");
        Response registration=request("账号验证","POST","https://api-user.huami.com/registrations/"+encode(account).replace("+","%20")+"/tokens",registrationHeaders,
                map("client_id","HuaMi","country_code","CN","json_response","true","name",account,"password",password,
                        "redirect_uri","https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html","state","REDIRECTION","token","access"));
        String access="";
        if(registration.status==200) {
            JSONObject value=json(registration.body,"账号验证");
            for(String key:new String[]{"access","access_token","code"}) {access=field(value,key); if(!access.isEmpty())break;}
        } else if(registration.status==302 || registration.status==303) access=queryValue(registration.header("Location"),"access");
        if(access.isEmpty())throw new ApiException("账号验证","MISSING_ACCESS","账号验证未返回有效令牌。请先确认官方 Zepp Life 可以正常登录。");
        progress.update("2 / 3  正在建立登录会话");
        Response login=request("登录会话","POST","https://api-mifit.zepp.com/v2/client/login",commonHeaders(),
                map("allow_registration","false","app_name",APP,"app_version","9.12.5","code",access,"country_code","CN",
                        "device_id","2C8B4939-0CCD-4E94-8CBA-CB8EA6E613A1","device_model","android_phone","dn",DN,"grant_type","access_token",
                        "lang","zh","source","com.huami.watch.hmwatchmanager:9.12.5:151689","third_name","huami"));
        JSONObject tokenInfo=json(login.body,"登录会话").optJSONObject("token_info");
        String loginToken=field(tokenInfo,"login_token"), userId=field(tokenInfo,"user_id");
        if(loginToken.isEmpty() || userId.isEmpty())throw new ApiException("登录会话","MISSING_LOGIN_TOKEN","登录响应缺少会话信息，可能是账号验证要求或接口变化。");
        progress.update("3 / 3  正在获取提交权限");
        Map<String,String> appHeaders=commonHeaders(); appHeaders.put("accept","application/json, text/plain, */*");
        Response app=request("提交令牌","GET","https://api-mifit.zepp.com/v1/client/app_tokens?"+form(map("app_name",APP,"dn",DN,"login_token",loginToken)),appHeaders,null);
        String appToken=field(json(app.body,"提交令牌").optJSONObject("token_info"),"app_token");
        if(appToken.isEmpty())throw new ApiException("提交令牌","MISSING_APP_TOKEN","未获取到步数提交令牌，请检查账号或接口兼容性。");
        return new Session(userId,appToken);
    }
    static String buildPayload(String template,int steps,Date now) throws ApiException {
        validateSteps(String.valueOf(steps));
        try {
            JSONArray rows=new JSONArray(template); JSONObject row=rows.getJSONObject(0);
            SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US); f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            row.put("date",f.format(now));
            JSONObject summary=new JSONObject(row.getString("summary"));
            summary.getJSONObject("stp").put("ttl",steps);
            row.put("summary",summary.toString());
            return rows.toString();
        }catch(Exception e){throw new ApiException("数据准备","PAYLOAD_ERROR","内置步数模板无效，请重新安装完整安装包。");}
    }
    void upload(Session session,int steps,String template,Progress progress) throws ApiException {
        progress.update("正在提交步数，请勿重复点击");
        long timestamp=System.currentTimeMillis();
        String payload=buildPayload(template,steps,new Date(timestamp));
        Response response=request("步数提交","POST","https://api-mifit-cn2.huami.com/v1/data/band_data.json?t="+timestamp,
                map("User-Agent","Dalvik/2.1.0 (Linux; U; Android 9; MI 6 MIUI/20.6.18)","apptoken",session.appToken,"Accept-Encoding","gzip"),
                map("userid",session.userId,"last_sync_data_time",String.valueOf(timestamp/1000),"device_type","0","last_deviceid","DA932FFFFE8816E7","data_json",payload));
        Object code=json(response.body,"步数提交").opt("code");
        if(!(code instanceof Number) || ((Number)code).doubleValue()!=1.0)
            throw new ApiException("步数提交","UPDATE_REJECTED","Zepp 未接受本次步数。请在官方应用检查账号、绑定状态或接口兼容性。");
    }

    /** Uses platform TLS verification and per-operation cookies; does not follow redirects with secrets. */
    private static final class HttpTransport implements Transport {
        private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        private final AtomicBoolean cancelled=new AtomicBoolean(false);
        private volatile HttpsURLConnection active;
        public void cancel(){cancelled.set(true); HttpsURLConnection c=active; if(c!=null)c.disconnect();}
        public Response execute(String method,String url,Map<String,String> headers,Map<String,String> data) throws Exception {
            if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            URL target=new URL(url);
            if(!"https".equals(target.getProtocol()) || !("api-user.huami.com".equals(target.getHost()) || "api-mifit.zepp.com".equals(target.getHost()) || "api-mifit-cn2.huami.com".equals(target.getHost())))
                throw new IOException("Disallowed destination");
            HttpsURLConnection connection=(HttpsURLConnection)target.openConnection(); active=connection;
            try {
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded;charset=UTF-8");
                for(Map.Entry<String,String> e:headers.entrySet())connection.setRequestProperty(e.getKey(),e.getValue());
                for(Map.Entry<String,List<String>> e:cookies.get(target.toURI(),Collections.<String,List<String>>emptyMap()).entrySet())
                    if(!e.getValue().isEmpty())connection.setRequestProperty(e.getKey(),String.join("; ",e.getValue()));
                if(data!=null) {
                    byte[] bytes=form(data).getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                    try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
                }
                if(cancelled.get())throw new InterruptedIOException();
                int status=connection.getResponseCode(); Map<String,List<String>> responseHeaders=connection.getHeaderFields();
                cookies.put(target.toURI(),responseHeaders);
                InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
                String body="";
                if(stream!=null) {
                    if("gzip".equalsIgnoreCase(connection.getContentEncoding()))stream=new GZIPInputStream(stream);
                    try(InputStream input=stream;ByteArrayOutputStream buffer=new ByteArrayOutputStream()) {
                        byte[] bytes=new byte[4096]; int n;
                        while((n=input.read(bytes))!=-1) {
                            if(cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                            if(buffer.size()+n>524288)throw new IOException("Oversized response");
                            buffer.write(bytes,0,n);
                        }
                        body=new String(buffer.toByteArray(),StandardCharsets.UTF_8);
                    }
                }
                return new Response(status,body,responseHeaders);
            } finally { active=null; connection.disconnect(); }
        }
    }
}
