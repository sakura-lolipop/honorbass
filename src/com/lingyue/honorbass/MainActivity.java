package com.lingyue.honorbass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 模块状态页（也提供桌面入口；hook 逻辑在 HonorBass，作用域为 system_server） */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 24);
        root.setPadding(pad, pad * 3, pad, pad);

        TextView title = new TextView(this);
        title.setText("HonorBass 尊享低音放行");
        title.setTextSize(22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText(
                "让「支持的应用」显示全部正常应用，并让非白名单应用真实获得尊享低音。\n\n"
                + "启用步骤：\n"
                + "1. LSPosed 管理器 → 模块 → HonorBass → 启用\n"
                + "2. 作用域勾选「系统」(system)\n"
                + "3. 重启手机\n"
                + "4. 设置 → 声音与触感 → HONOR Sound 音效 → 外放音效 → 尊享低音 → 支持的应用\n\n"
                + "说明：本模块无运行时设置，列表里每个应用的开关即为每应用低音开关，正常持久化。\n\n"
                + "作者：铃月");
        info.setTextSize(14);
        info.setLineSpacing(0, 1.2f);
        root.addView(info);

        Button btn = new Button(this);
        btn.setText("打开 HONOR Sound 音效设置");
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent().setClassName(
                        "com.hihonor.imedia.sws", "com.hihonor.imedia.sws.MainActivity"));
            } catch (Exception ignored) {
            }
        });
        root.addView(btn);

        setContentView(root);
    }
}
