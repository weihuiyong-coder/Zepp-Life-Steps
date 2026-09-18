package com.weihuiyong.zeppsteps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int INK=Color.rgb(25,43,53), MUTED=Color.rgb(100,119,130), TEAL=Color.rgb(8,127,117), BG=Color.rgb(244,247,249);
    private EditText account,password,steps;
    private CheckBox remember,showPassword;
    private Button submit,check,clear;
    private TextView status,lastResult;
    private ProgressBar spinner;
    private SecureVault vault;
    private SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile ZeppClient client;
    private boolean busy=false;
    private long nextAllowed=0;
    private String template="";
    private final ArrayList<View> formControls=new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        vault=new SecureVault(this); prefs=getSharedPreferences("settings",MODE_PRIVATE);
        nextAllowed=prefs.getLong("nextAllowed",0);
        if(nextAllowed>System.currentTimeMillis()+86400000L)nextAllowed=0;
        buildUi();
        try(InputStream input=getAssets().open("step-template.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);
            template=new String(out.toByteArray(),StandardCharsets.UTF_8);
            ZeppClient.buildPayload(template,100,new Date());
        }catch(Exception e){status.setText("内置模板读取失败，请重新安装完整 APK。");submit.setEnabled(false);check.setEnabled(false);}
        try {
            String[] saved=vault.load();
            if(saved!=null){account.setText(saved[0]);password.setText(saved[1]);remember.setChecked(true);}
        }catch(Exception e){status.setText("以前保存的凭据无法解密，请重新输入；不会改为明文保存。");}
        refreshLast();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void add(LinearLayout parent,View view,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);}
    private EditText input(String hint,int type,int limit){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(17);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setSingleLine(true);e.setInputType(type);e.setPadding(dp(14),dp(15),dp(14),dp(15));e.setBackground(background(BG,12));e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);formControls.add(e);return e;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(primary?Color.WHITE:TEAL);b.setBackground(background(primary?TEAL:Color.rgb(228,242,239),12));b.setStateListAnimator(null);b.setMinHeight(dp(52));b.setPadding(dp(12),dp(10),dp(12),dp(10));formControls.add(b);return b;}
    private void buildUi(){
        LinearLayout frame=column();frame.setBackgroundColor(BG);
        frame.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);frame.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout root=column();root.setPadding(dp(22),dp(24),dp(22),dp(24));scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        add(root,text("ZEPP  /  本机直连",12,TEAL,true),0);
        add(root,text("步数助手",32,INK,true),8);
        add(root,text("手机独立运行，无需电脑或服务器",14,MUTED,false),5);
        LinearLayout card=column();card.setPadding(dp(18),dp(20),dp(18),dp(20));card.setBackground(background(Color.WHITE,20));add(root,card,24);
        add(card,text("Zepp Life 账号",14,INK,true),0);
        account=input("手机号或邮箱，不是微信号",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,254);add(card,account,8);
        add(card,text("密码",14,INK,true),18);
        password=input("输入 Zepp Life 登录密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,256);password.setTransformationMethod(PasswordTransformationMethod.getInstance());add(card,password,8);
        showPassword=new CheckBox(this);showPassword.setText("显示密码");showPassword.setTextColor(MUTED);showPassword.setTextSize(13);showPassword.setOnCheckedChangeListener((b,on)->{password.setTransformationMethod(on?null:PasswordTransformationMethod.getInstance());password.setSelection(password.length());});formControls.add(showPassword);add(card,showPassword,2);
        add(card,text("今天的目标总步数",14,INK,true),14);
        steps=input("0 — 100000",InputType.TYPE_CLASS_NUMBER,6);steps.setText(String.valueOf(prefs.getInt("steps",12000)));steps.setTextSize(28);add(card,steps,8);
        TextView date=text("按北京时间当天提交；不是在原步数上累加。",12,MUTED,false);add(card,date,7);
        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);
        for(final int count:new int[]{8000,12000,20000}){Button b=button(String.format(Locale.US,"%,d",count),false);b.setTextSize(13);b.setMinHeight(dp(42));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);if(count!=8000)p.leftMargin=dp(8);presets.addView(b,p);b.setOnClickListener(v->steps.setText(String.valueOf(count)));}add(card,presets,12);
        remember=new CheckBox(this);remember.setText("加密保存在本机（可选）");remember.setTextColor(INK);remember.setTextSize(13);formControls.add(remember);add(card,remember,16);
        remember.setOnCheckedChangeListener((button,on)->{if(!on){try{vault.clear();}catch(Exception e){status.setText("清除保存信息失败，可到系统设置清除此应用的数据。");}}});
        submit=button("提交步数",true);submit.setOnClickListener(v->confirmUpload());add(card,submit,12);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        check=button("验证登录",false);clear=button("清除账号",false);
        LinearLayout.LayoutParams p1=new LinearLayout.LayoutParams(0,dp(48),1),p2=new LinearLayout.LayoutParams(0,dp(48),1);p2.leftMargin=dp(10);actions.addView(check,p1);actions.addView(clear,p2);add(card,actions,10);
        check.setOnClickListener(v->start(false));clear.setOnClickListener(v->clearAccount());
        LinearLayout result=column();result.setPadding(dp(17),dp(17),dp(17),dp(17));result.setBackground(background(Color.WHITE,18));add(root,result,16);
        add(result,text("操作状态",14,INK,true),0);spinner=new ProgressBar(this);spinner.setIndeterminate(true);spinner.setVisibility(View.GONE);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(28),dp(28));sp.topMargin=dp(10);result.addView(spinner,sp);
        status=text("准备就绪。首次使用可先验证登录。",14,MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(result,status,8);
        lastResult=text("",12,MUTED,false);add(result,lastResult,12);
        TextView help=text("使用说明与隐私",14,TEAL,true);help.setPadding(0,dp(12),0,dp(12));help.setOnClickListener(v->showHelp());add(root,help,14);
        add(root,text("v1.0.0  ·  原生 Android 版\n仅用于本人账号；非 Zepp 或微信官方应用。",11,MUTED,false),0);
        root.setFocusableInTouchMode(true);root.requestFocus();setContentView(frame);frame.requestApplyInsets();
    }
    private boolean validate(boolean upload){
        if(busy)return false;
        try{ZeppClient.normalizeAccount(account.getText().toString());if(password.length()==0)throw new ZeppClient.ApiException("输入","PASSWORD","请输入 Zepp Life 密码。");if(upload)ZeppClient.validateSteps(steps.getText().toString());}
        catch(ZeppClient.ApiException e){status.setText(e.getMessage());return false;}
        long remaining=(nextAllowed-System.currentTimeMillis()+999)/1000;
        if(remaining>0){status.setText("请在 "+remaining+" 秒后再试，避免接口频率限制。");return false;}
        return true;
    }
    private void confirmUpload(){
        if(!validate(true))return;
        new AlertDialog.Builder(this).setTitle("确认提交今天的总步数")
                .setMessage("将北京时间今天的目标总步数提交为 "+steps.getText()+" 步。\n\n这不是追加步数。请仅操作你自己的账号。")
                .setNegativeButton("取消",null).setPositiveButton("确认提交",(d,w)->start(true)).show();
    }
    private void setBusy(boolean value){busy=value;for(View v:formControls)v.setEnabled(!value);spinner.setVisibility(value?View.VISIBLE:View.GONE);submit.setText(value?"正在处理…":"提交步数");}
    private void start(final boolean upload){
        if(!validate(upload))return;
        final String a=account.getText().toString().trim(),p=password.getText().toString();
        final int count;try{count=upload?ZeppClient.validateSteps(steps.getText().toString()):0;}catch(Exception e){return;}
        final boolean store=remember.isChecked();
        if(upload)prefs.edit().putInt("steps",count).apply();
        View focus=getCurrentFocus();if(focus!=null)((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);
        setBusy(true);status.setText("正在准备安全连接…");
        nextAllowed=System.currentTimeMillis()+20000L;prefs.edit().putLong("nextAllowed",nextAllowed).apply();
        client=new ZeppClient();final ZeppClient operation=client;
        worker.execute(()->{
            String message;boolean accepted=false;int cooldown=0;String storedWarning="";
            try{
                if(store)vault.save(a,p);else vault.clear();
            }catch(Exception ignored){storedWarning="\n凭据未能保存或清除，请检查本机存储；不会使用明文保存。";}
            try{
                ZeppClient.Progress progress=value->main.post(()->{if(!isFinishing()&&!isDestroyed())status.setText(value);});
                ZeppClient.Session session=operation.login(a,p,progress);
                if(upload){operation.upload(session,count,template,progress);accepted=true;message="Zepp 已接受 "+String.format(Locale.US,"%,d",count)+" 步。\n微信是否同步成功，请打开微信运动确认。";}
                else message="登录验证通过，已获取提交令牌。\n本次没有修改步数。";
            }catch(ZeppClient.ApiException e){cooldown=e.retryAfter;message=e.getMessage()+"\n阶段："+e.stage+"  /  "+e.code;if(cooldown>0)message+="\n请在 "+cooldown+" 秒后再试。";if(upload && ("TIMEOUT".equals(e.code)||"NETWORK_ERROR".equals(e.code)) && "步数提交".equals(e.stage))message+="\n提交结果暂不确定，请先检查 Zepp/微信，避免重复提交。";}
            catch(Exception e){message="处理未完成，请重新打开应用后再试。未自动重复提交。";}
            final String result=message+storedWarning;final boolean success=accepted;final int waitSeconds=cooldown;
            main.post(()->{
                if(isFinishing()||isDestroyed())return;
                if(waitSeconds>0){nextAllowed=Math.max(nextAllowed,System.currentTimeMillis()+waitSeconds*1000L);prefs.edit().putLong("nextAllowed",nextAllowed).apply();}
                if(success){SimpleDateFormat f=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));prefs.edit().putString("last",f.format(new Date())+"  ·  "+count+" 步（Zepp 接受，北京时间）").apply();}
                setBusy(false);status.setText(result);refreshLast();client=null;
            });
        });
    }
    private void refreshLast(){String last=prefs.getString("last","");lastResult.setVisibility(last.isEmpty()?View.GONE:View.VISIBLE);lastResult.setText("最近提交："+last);}
    private void clearAccount(){try{vault.clear();account.setText("");password.setText("");remember.setChecked(false);status.setText("已清除保存的账号和密码。");}catch(Exception e){status.setText("清除失败，请到手机系统设置中清除此应用的数据。");}}
    private void showHelp(){new AlertDialog.Builder(this).setTitle("使用说明与隐私").setMessage(
            "1. 使用与电脑版相同的 Zepp Life 手机号/邮箱和密码，不是微信密码。\n\n2. 先在官方 Zepp Life 中绑定微信运动；绑定步骤以官方应用当前界面为准。\n\n3. 输入今天的目标总步数并提交。显示“Zepp 已接受”不代表微信已完成同步，需要到微信运动确认。\n\n4. APK 在手机本机通过 HTTPS 连接仓库原有的 Zepp/华米接口，不连接你的电脑，不需要 Termux、Root 或自建服务器。\n\n5. 默认不持久保存账号密码。勾选保存后使用 Android Keystore 和 AES-GCM 加密，仅在本机保存；不写入日志，不使用分析服务，不上传到第三方中转服务器。\n\n6. 不自动重试，不后台刷步数。接口限流时请等待提示时间。非官方接口可能变化或触发账号验证。\n\n7. 仅供本人账号使用，不用于竞赛、保险、奖励或其他依赖真实运动记录的活动。\n\n版本 1.0.0；基于仓库提交 8cb3191 的登录和提交逻辑。")
            .setPositiveButton("知道了",null).show();}
    @Override protected void onDestroy(){ZeppClient current=client;if(current!=null)current.cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
