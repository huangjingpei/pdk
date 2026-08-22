/*
 * PDK C++ SDK 使用示例
 *
 * 演示：
 *   1. 注册状态/事件/调试日志三类回调（实时把“现在是什么状态”告诉开发者/客户）；
 *   2. 注册 -> 申请并解密短效 Token -> 向拼多多官方发包 -> 上报结果 -> 查询配额；
 *   3. 处理设备互踢（40103）等典型错误。
 *
 * 编译见 CMakeLists.txt（或 README.md）。运行：
 *   ./pdk_example [base_url] [phone] [password] [sms_code]
 */
#include "pdk/pdk.hpp"

#include <iostream>
#include <string>

using namespace pdk;

int main(int argc, char** argv) {
    pdk::enable_utf8_console();   // 关键：Windows 控制台按 UTF-8 显示中文，避免乱码

    std::string baseUrl  = argc > 1 ? argv[1] : "http://localhost:8080";
    std::string phone    = argc > 2 ? argv[2] : "13800138000";
    std::string password = argc > 3 ? argv[3] : "Pdk12345678";
    std::string smsCode  = argc > 4 ? argv[4] : "123456";

    Config cfg;
    cfg.baseUrl        = baseUrl;
    cfg.enableDebugLog = true;   // 打开后会在 log 回调里看到 [请求]/[响应]/[期待]

    Client client(cfg);

    // —— 1. 状态回调：告诉界面“现在处于什么状态” ——
    client.setStateCallback([](State s, const std::string& detail) {
        std::cout << "[状态] " << Client::describeState(s) << " —— " << detail << "\n";
    });

    // —— 2. 事件回调：更细粒度的事件（解密成功/配额耗尽等）——
    client.setEventCallback([](Event e, const std::string& msg) {
        std::cout << "[事件] " << Client::describeEvent(e) << " —— " << msg << "\n";
    });

    // —— 3. 调试日志回调：每条 HTTP 的请求/响应/期待 ——
    client.setLogCallback([](const std::string& line) {
        std::cout << "[调试] " << line << "\n";
    });

    //std::cout << "== 设备ID: " << client.deviceId() << " ==\n";

    // —— 发送短信验证码 ——
    auto r = client.sendSms(phone, "REGISTER");
    if (!r.ok()) {
        std::cout << "发送短信失败: code=" << r.code << " msg=" << r.message << "\n";
        return 1;
    }

    // —— 注册（若已注册会返回 40010，可改调 login）——
    r = client.registerAccount(phone, password, smsCode);
    if (!r.ok()) {
        std::cout << "注册失败: code=" << r.code << " msg=" << r.message << "\n";
        if (r.code == ResultCode::TRIAL_ALREADY_CLAIMED) {
            std::cout << "（已领取过体验，改用 login 登录）\n";
            r = client.login(phone, password);
            if (!r.ok()) { std::cout << "登录失败: " << r.message << "\n"; return 1; }
        } else {
            return 1;
        }
    }

    // —— 查询账号配额 ——
    r = client.profile();
    if (r.ok()) {
        std::cout << "剩余次数: " << r.dataLong("remainingCalls")
                  << "  套餐: " << r.dataString("packageName") << "\n";
    }

    // —— 申请并解密短效 Token（核心调度）——
    std::string plain;
    r = client.acquireTokenDecrypted("GOODS_COLLECT", "881920391204", plain);
    if (r.ok() && !plain.empty()) {
        std::cout << "解密后的明文 Token 报文: " << plain << "\n";
        std::cout << "（此处开发者用明文里的拼多多 Session 向官方发起采集请求）\n";

        // 提取租约号用于上报
        std::string traceId = r.dataString("leaseTraceId");
        // —— 业务执行完成后上报结果：SUCCESS 扣 1 次 ——
        auto rr = client.reportResult(traceId, "SUCCESS", 1200, "");
        std::cout << "上报结果: code=" << rr.code << " msg=" << rr.message << "\n";
    } else {
        std::cout << "申请 Token 失败: code=" << r.code << " msg=" << r.message << "\n";
        if (r.code == ResultCode::DEVICE_KICK_OUT)
            std::cout << "（账号已在其他设备登录，请先在原设备解绑）\n";
    }

    // —— 注销 ——
    client.logout();
    std::cout << "结束。\n";
    return 0;
}
