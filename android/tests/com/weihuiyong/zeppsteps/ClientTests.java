package com.weihuiyong.zeppsteps;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class ClientTests {
    static int checks=0;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS: "+name);}
    static ZeppClient.Response response(int code,String body){return new ZeppClient.Response(code,body,new HashMap<String,List<String>>());}
    static final class Fake implements ZeppClient.Transport {
        final ArrayDeque<ZeppClient.Response> replies=new ArrayDeque<>();
        final List<String> urls=new ArrayList<>();final List<Map<String,String>> forms=new ArrayList<>(),headers=new ArrayList<>();
        public ZeppClient.Response execute(String method,String url,Map<String,String> h,Map<String,String> form){urls.add(url);headers.add(h);forms.add(form);return replies.remove();}
        public void cancel(){}
    }
    static Fake good(){Fake f=new Fake();f.replies.add(response(200,"{\"access\":\"test-access\"}"));f.replies.add(response(200,"{\"token_info\":{\"login_token\":\"test-login\",\"user_id\":123}}"));f.replies.add(response(200,"{\"token_info\":{\"app_token\":\"test-app\"}}"));return f;}
    interface Throwing {void run()throws Exception;}
    static void error(String code,Throwing body)throws Exception{try{body.run();throw new AssertionError("Expected "+code);}catch(ZeppClient.ApiException e){check(code.equals(e.code),"Reject "+code);}}
    public static void main(String[] args)throws Exception {
        check("+8613800000000".equals(ZeppClient.normalizeAccount(" 13800000000 ")),"Chinese phone normalization");
        check("+819000000000".equals(ZeppClient.normalizeAccount("+819000000000")),"Explicit country prefix preserved");
        check("a+b@example.com".equals(ZeppClient.normalizeAccount("a+b@example.com")),"Email plus sign preserved");
        error("INVALID_ACCOUNT",()->ZeppClient.normalizeAccount("weixin-name"));
        check(ZeppClient.validateSteps("0")==0,"Zero step boundary");check(ZeppClient.validateSteps("100000")==100000,"Maximum step boundary");
        error("INVALID_STEPS",()->ZeppClient.validateSteps("100001"));error("INVALID_STEPS",()->ZeppClient.validateSteps("-1"));error("INVALID_STEPS",()->ZeppClient.validateSteps("1.5"));error("INVALID_STEPS",()->ZeppClient.validateSteps(""));
        check("x=a%2Bb%26%3D+%E4%B8%AD".equals(ZeppClient.form(ZeppClient.map("x","a+b&= 中"))),"UTF-8 form and special character encoding");
        String template=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        String payload=ZeppClient.buildPayload(template,34567,new Date(0));JSONObject row=new JSONArray(payload).getJSONObject(0);
        check("1970-01-01".equals(row.getString("date")),"Date uses Asia/Shanghai");
        check(new JSONObject(row.getString("summary")).getJSONObject("stp").getInt("ttl")==34567,"Exact target step count");
        check(row.getString("data_hr").equals(new JSONArray(template).getJSONObject(0).getString("data_hr")),"Original heart-rate payload preserved");
        check(row.getJSONArray("data").toString().equals(new JSONArray(template).getJSONObject(0).getJSONArray("data").toString()),"Original band payload preserved");
        JSONObject midnight=new JSONArray(ZeppClient.buildPayload(template,1,new Date(57600000L))).getJSONObject(0);
        check("1970-01-02".equals(midnight.getString("date")),"Beijing midnight rollover independent of device timezone");
        Fake f=good();ZeppClient client=new ZeppClient(f);ZeppClient.Session session=client.login("a+b@example.com"," p&+中 ",v->{});
        check(f.urls.size()==3,"Three-stage native login");check(f.urls.get(0).contains("a%2Bb%40example.com"),"Encoded account path");
        check(" p&+中 ".equals(f.forms.get(0).get("password")),"Password whitespace is not trimmed");
        check("test-access".equals(f.forms.get(1).get("code")),"Access code exchange");check(f.urls.get(2).contains("login_token=test-login"),"Login token exchange");
        check("123".equals(session.userId)&&"test-app".equals(session.appToken),"Numeric user ID and app token parsed");
        check("gzip".equals(f.headers.get(0).get("Accept-Encoding")),"Only supported compression advertised");
        f.replies.add(response(200,"{\"code\":1}"));client.upload(session,45678,template,v->{});
        check("test-app".equals(f.headers.get(3).get("apptoken")),"Upload app token header");
        check(f.urls.get(3).startsWith("https://api-mifit-cn2.huami.com/v1/data/band_data.json?t="),"Desktop upload endpoint preserved");
        check(new JSONObject(new JSONArray(f.forms.get(3).get("data_json")).getJSONObject(0).getString("summary")).getJSONObject("stp").getInt("ttl")==45678,"Submitted payload contains requested steps");
        Fake redirect=good();redirect.replies.removeFirst();Map<String,List<String>> h=new HashMap<>();h.put("Location",Arrays.asList("https://example.com/cb?access=code%2Bvalue&state=ok"));redirect.replies.addFirst(new ZeppClient.Response(302,"",h));new ZeppClient(redirect).login("13800000000","p",v->{});
        check("code+value".equals(redirect.forms.get(1).get("code")),"Redirect access code decoded without following redirect");
        Fake limited=new Fake();Map<String,List<String>> rate=new HashMap<>();rate.put("Retry-After",Arrays.asList("123"));limited.replies.add(new ZeppClient.Response(429,"",rate));
        try{new ZeppClient(limited).login("13800000000","p",v->{});throw new AssertionError();}catch(ZeppClient.ApiException e){check(e.retryAfter==123,"429 Retry-After respected");}
        check(limited.urls.size()==1,"No automatic retries");
        Fake denied=new Fake();denied.replies.add(response(401,"secret error body"));error("LOGIN_REJECTED",()->new ZeppClient(denied).login("13800000000","secret",v->{}));
        Fake invalid=new Fake();invalid.replies.add(response(200,"<html>proxy</html>"));error("INVALID_RESPONSE",()->new ZeppClient(invalid).login("13800000000","p",v->{}));
        Fake empty=new Fake();empty.replies.add(response(200,"{}"));error("MISSING_ACCESS",()->new ZeppClient(empty).login("13800000000","p",v->{}));
        Fake rejected=new Fake();rejected.replies.add(response(200,"{\"code\":0}"));error("UPDATE_REJECTED",()->new ZeppClient(rejected).upload(session,1,template,v->{}));
        Fake stringCode=new Fake();stringCode.replies.add(response(200,"{\"code\":\"1\"}"));error("UPDATE_REJECTED",()->new ZeppClient(stringCode).upload(session,1,template,v->{}));
        check(ZeppClient.retryAfter("nonsense")==60,"Safe rate-limit fallback");
        System.out.println("TOTAL: "+checks+" checks passed. Fixtures only; no live account was used.");
    }
}
