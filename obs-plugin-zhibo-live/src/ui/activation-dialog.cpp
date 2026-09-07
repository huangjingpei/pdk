#include "activation-dialog.hpp"
#include "../zhibo-config.hpp"
#include "../zhibo-auth-client.hpp"
#include "../zhibo-stream-poller.hpp"
#include "../zhibo-obs-source-manager.hpp"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QMessageBox>
#include <QApplication>
#include <obs-frontend-api.h>

namespace zhibo {

static ActivationDialog *s_dialog_instance = nullptr;

void ActivationDialog::show_dialog(QWidget *parent, const QString &initial_error) {
    if (!parent) {
        parent = static_cast<QWidget *>(obs_frontend_get_main_window());
    }

    if (!s_dialog_instance) {
        s_dialog_instance = new ActivationDialog(parent);
    } else {
        s_dialog_instance->adjust_geometry_to_parent();
    }

    if (!initial_error.isEmpty()) {
        s_dialog_instance->message_label_->setText(initial_error);
        s_dialog_instance->message_label_->setStyleSheet("color: #ff4d4f; font-weight: bold;");
    }

    s_dialog_instance->update_status_ui();
    s_dialog_instance->show();
    s_dialog_instance->raise();
    s_dialog_instance->activateWindow();
}

ActivationDialog::ActivationDialog(QWidget *parent) : QDialog(parent) {
    setWindowTitle(tr("智播云控 - 客户端设备激活"));
    setWindowFlags(windowFlags() & ~Qt::WindowContextHelpButtonHint);

    init_ui();
    adjust_geometry_to_parent();
    load_config_to_ui();
    update_status_ui();
}

void ActivationDialog::adjust_geometry_to_parent() {
    QWidget *pw = parentWidget();
    if (!pw) {
        pw = static_cast<QWidget *>(obs_frontend_get_main_window());
    }
    if (pw) {
        QRect parent_geo = pw->geometry();
        int pw_w = parent_geo.width();
        int pw_h = parent_geo.height();

        // 依据父窗口长宽比例与尺寸，自适应计算弹窗大小（比例与父窗口保持协调，缩放系数约 0.46）
        double scale = 0.46;
        int target_w = static_cast<int>(pw_w * scale);
        int target_h = static_cast<int>(pw_h * scale);

        // 设定边界阈值，确保在不同分辨率和缩放下，控件布局完整美观
        if (target_w < 680) target_w = 680;
        if (target_w > 960) target_w = 960;
        if (target_h < 420) target_h = 420;
        if (target_h > 580) target_h = 580;

        resize(target_w, target_h);

        // 居中显示在父窗口内部
        int x = parent_geo.x() + (pw_w - target_w) / 2;
        int y = parent_geo.y() + (pw_h - target_h) / 2;
        move(x, y);
    } else {
        resize(720, 440);
    }
}

void ActivationDialog::init_ui() {
    QVBoxLayout *main_layout = new QVBoxLayout(this);
    main_layout->setSpacing(14);
    main_layout->setContentsMargins(18, 18, 18, 18);

    // 左右双栏卡片布局
    QHBoxLayout *cards_layout = new QHBoxLayout();
    cards_layout->setSpacing(14);

    // 1. 左栏：账号与设备绑定卡片
    QGroupBox *auth_group = new QGroupBox(tr("账号与设备绑定"), this);
    QFormLayout *form_auth = new QFormLayout(auth_group);
    form_auth->setLabelAlignment(Qt::AlignRight | Qt::AlignVCenter);
    form_auth->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    form_auth->setSpacing(10);

    local_env_check_ = new QCheckBox(tr("使用本地联调环境 (127.0.0.1:8080)"));
    form_auth->addRow(tr("环境设置:"), local_env_check_);

    phone_edit_ = new QLineEdit();
    phone_edit_->setPlaceholderText(tr("请输入注册手机号"));
    form_auth->addRow(tr("手机号*:"), phone_edit_);

    password_edit_ = new QLineEdit();
    password_edit_->setEchoMode(QLineEdit::Password);
    password_edit_->setPlaceholderText(tr("请输入登录密码"));
    form_auth->addRow(tr("登录密码*:"), password_edit_);

    card_key_edit_ = new QLineEdit();
    card_key_edit_->setPlaceholderText(tr("新设备首次激活必填 (卡密格式: PDK-...)"));
    form_auth->addRow(tr("设备卡密:"), card_key_edit_);

    device_id_label_ = new QLabel();
    device_id_label_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    device_id_label_->setWordWrap(true);
    device_id_label_->setStyleSheet("color: #666; font-family: Consolas, monospace; font-size: 11px;");
    form_auth->addRow(tr("设备标识:"), device_id_label_);

    cards_layout->addWidget(auth_group, 1);

    // 2. 右栏：授权状态与推流看板卡片
    QGroupBox *status_group = new QGroupBox(tr("当前设备许可证与拉流状态"), this);
    QFormLayout *form_status = new QFormLayout(status_group);
    form_status->setLabelAlignment(Qt::AlignRight | Qt::AlignVCenter);
    form_status->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    form_status->setSpacing(10);

    status_badge_ = new QLabel(tr("未激活"));
    status_badge_->setStyleSheet("font-weight: bold;");
    form_status->addRow(tr("授权状态:"), status_badge_);

    expire_label_ = new QLabel(tr("--"));
    form_status->addRow(tr("到期时间:"), expire_label_);

    calls_label_ = new QLabel(tr("--"));
    form_status->addRow(tr("剩余计费次数:"), calls_label_);

    stream_status_label_ = new QLabel(tr("空闲 / 等待推流中"));
    stream_status_label_->setWordWrap(true);
    stream_status_label_->setTextInteractionFlags(Qt::TextSelectableByMouse);
    form_status->addRow(tr("推流拉流状态:"), stream_status_label_);

    cards_layout->addWidget(status_group, 1);

    main_layout->addLayout(cards_layout);

    // 3. 运行选项
    auto_pull_check_ = new QCheckBox(tr("探测到活动直播时自动接入 OBS 场景 (智播拉流源)"), this);
    auto_pull_check_->setChecked(true);
    main_layout->addWidget(auto_pull_check_);

    // 4. 底部消息提示
    message_label_ = new QLabel();
    message_label_->setWordWrap(true);
    message_label_->setStyleSheet("color: #666; min-height: 20px;");
    main_layout->addWidget(message_label_);

    // 5. 底部按钮栏
    QHBoxLayout *btn_layout = new QHBoxLayout();
    btn_layout->addStretch();

    activate_btn_ = new QPushButton(tr("立即激活 / 登录"));
    activate_btn_->setStyleSheet("background-color: #1890ff; color: white; padding: 7px 24px; font-weight: bold; border-radius: 4px;");
    connect(activate_btn_, &QPushButton::clicked, this, &ActivationDialog::on_activate_clicked);
    btn_layout->addWidget(activate_btn_);

    close_btn_ = new QPushButton(tr("关闭"));
    close_btn_->setStyleSheet("padding: 7px 22px; border-radius: 4px;");
    connect(close_btn_, &QPushButton::clicked, this, &QDialog::hide);
    btn_layout->addWidget(close_btn_);

    main_layout->addLayout(btn_layout);
}

void ActivationDialog::load_config_to_ui() {
    auto &cfg = ZhiboConfig::instance();
    local_env_check_->setChecked(cfg.get_environment() == PdkEnv::LOCAL_DEBUG);

    phone_edit_->setText(QString::fromStdString(cfg.get_phone()));
    password_edit_->setText(QString::fromStdString(cfg.get_password()));
    card_key_edit_->setText(QString::fromStdString(cfg.get_card_key()));
    device_id_label_->setText(QString::fromStdString(ZhiboAuthClient::instance().get_or_create_device_id()));
    auto_pull_check_->setChecked(cfg.is_auto_pull());
}

void ActivationDialog::update_status_ui() {
    auto &cfg = ZhiboConfig::instance();
    std::string status = cfg.get_license_status();
    std::string expire = cfg.get_expire_at();
    int calls = cfg.get_remaining_calls();

    if (status == "ACTIVE") {
        status_badge_->setText(tr("已激活 (正常)"));
        status_badge_->setStyleSheet("color: #52c41a; font-weight: bold;");
    } else if (status == "SUSPENDED") {
        status_badge_->setText(tr("已暂停"));
        status_badge_->setStyleSheet("color: #faad14; font-weight: bold;");
    } else if (status == "EXPIRED") {
        status_badge_->setText(tr("已到期"));
        status_badge_->setStyleSheet("color: #ff4d4f; font-weight: bold;");
    } else {
        status_badge_->setText(tr("未激活"));
        status_badge_->setStyleSheet("color: #8c8c8c; font-weight: bold;");
    }

    expire_label_->setText(expire.empty() ? tr("未激活 · 暂不计时") : QString::fromStdString(expire));
    calls_label_->setText(calls >= 2147483647 ? tr("不限") : QString::number(calls));

    if (ZhiboObsSourceManager::instance().is_streaming()) {
        std::string url = ZhiboObsSourceManager::instance().get_current_rtmp_url();
        stream_status_label_->setText(tr("正在拉流播放中:\n") + QString::fromStdString(url));
        stream_status_label_->setStyleSheet("color: #52c41a; font-weight: bold; font-family: Consolas, monospace; font-size: 11px;");
    } else {
        stream_status_label_->setText(tr("空闲 / 等待推流中..."));
        stream_status_label_->setStyleSheet("color: #8c8c8c;");
    }
}

void ActivationDialog::on_activate_clicked() {
    auto &cfg = ZhiboConfig::instance();
    PdkEnv env = local_env_check_->isChecked() ? PdkEnv::LOCAL_DEBUG : PdkEnv::PRODUCTION;
    cfg.set_environment(env);

    QString phone = phone_edit_->text().trimmed();
    QString password = password_edit_->text().trimmed();
    QString card_key = card_key_edit_->text().trimmed();

    if (phone.isEmpty() || password.isEmpty()) {
        message_label_->setText(tr("请完整输入手机号和密码"));
        message_label_->setStyleSheet("color: #ff4d4f; font-weight: bold;");
        return;
    }

    cfg.set_phone(phone.toStdString());
    cfg.set_password(password.toStdString());
    cfg.set_card_key(card_key.toStdString());
    cfg.set_auto_pull(auto_pull_check_->isChecked());
    cfg.save();

    message_label_->setText(tr("正在连接 PDK 服务端验证与激活..."));
    message_label_->setStyleSheet("color: #1890ff;");
    activate_btn_->setEnabled(false);
    QApplication::processEvents();

    AuthResult res = ZhiboAuthClient::instance().login(phone.toStdString(), password.toStdString(), card_key.toStdString());
    activate_btn_->setEnabled(true);

    if (res.success) {
        message_label_->setText(tr("激活成功！已绑定本设备席位，开始监听活动推流。"));
        message_label_->setStyleSheet("color: #52c41a; font-weight: bold;");
        update_status_ui();

        // 启动后台流探测
        ZhiboStreamPoller::instance().start();
        ZhiboStreamPoller::instance().trigger_once();
    } else {
        if (res.need_card_key) {
            message_label_->setText(tr("【需要卡密】当前设备未激活，请输入分配给该手机号的卡密后重试。"));
            card_key_edit_->setFocus();
        } else {
            message_label_->setText(QString::fromStdString("激活/登录失败 [" + std::to_string(res.code) + "]: " + res.message));
        }
        message_label_->setStyleSheet("color: #ff4d4f; font-weight: bold;");
        update_status_ui();
    }
}

} // namespace zhibo
