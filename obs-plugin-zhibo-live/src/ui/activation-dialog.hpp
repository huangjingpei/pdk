#pragma once

#include <QDialog>
#include <QLineEdit>
#include <QLabel>
#include <QPushButton>
#include <QCheckBox>
#include <QGroupBox>

namespace zhibo {

class ActivationDialog : public QDialog {
public:
    explicit ActivationDialog(QWidget *parent = nullptr);
    ~ActivationDialog() override = default;

    static void show_dialog(QWidget *parent = nullptr, const QString &initial_error = "");

    void on_activate_clicked();
    void update_status_ui();
    void adjust_geometry_to_parent();

private:
    void init_ui();
    void load_config_to_ui();

    // 运行环境选项
    QCheckBox *local_env_check_ = nullptr;

    // 账号与设备凭据
    QLineEdit *phone_edit_ = nullptr;
    QLineEdit *password_edit_ = nullptr;
    QLineEdit *card_key_edit_ = nullptr;
    QLabel *device_id_label_ = nullptr;

    // 许可证状态看板
    QLabel *status_badge_ = nullptr;
    QLabel *expire_label_ = nullptr;
    QLabel *calls_label_ = nullptr;
    QLabel *stream_status_label_ = nullptr;

    // 运行选项
    QCheckBox *auto_pull_check_ = nullptr;

    // 底部提示与操作按钮
    QLabel *message_label_ = nullptr;
    QPushButton *activate_btn_ = nullptr;
    QPushButton *close_btn_ = nullptr;
};

} // namespace zhibo
