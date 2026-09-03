#include "crypto.hpp"

#include <bcrypt.h>
#include <wincrypt.h>
#include <monocypher-ed25519.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <fstream>
#include <iomanip>
#include <memory>
#include <sstream>
#include <vector>

namespace pdk {

std::vector<unsigned char> decode_base64(const std::string& encoded) {
    DWORD size = 0;
    if (!CryptStringToBinaryA(encoded.c_str(), static_cast<DWORD>(encoded.size()),
                              CRYPT_STRING_BASE64, nullptr, &size, nullptr, nullptr)) {
        throw UpdateError(windows_error("base64 length decode failed"));
    }
    std::vector<unsigned char> output(size);
    if (!CryptStringToBinaryA(encoded.c_str(), static_cast<DWORD>(encoded.size()),
                              CRYPT_STRING_BASE64, output.data(), &size, nullptr, nullptr)) {
        throw UpdateError(windows_error("base64 decode failed"));
    }
    output.resize(size);
    return output;
}

std::string sha256_file(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    std::vector<unsigned char> object;
    std::array<unsigned char, 32> digest{};
    auto cleanup = [&] {
        if (hash) BCryptDestroyHash(hash);
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
    };
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) {
        throw UpdateError("BCrypt SHA-256 provider unavailable");
    }
    DWORD object_size = 0, copied = 0;
    if (BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH,
                          reinterpret_cast<PUCHAR>(&object_size), sizeof(object_size), &copied, 0) < 0) {
        cleanup();
        throw UpdateError("cannot read BCrypt hash object size");
    }
    object.resize(object_size);
    if (BCryptCreateHash(algorithm, &hash, object.data(), object_size, nullptr, 0, 0) < 0) {
        cleanup();
        throw UpdateError("cannot create BCrypt SHA-256 hash");
    }
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        cleanup();
        throw UpdateError("cannot open package for SHA-256: " + path_to_utf8(path));
    }
    std::vector<char> buffer(256 * 1024);
    while (input) {
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        const auto count = input.gcount();
        if (count > 0 && BCryptHashData(hash, reinterpret_cast<PUCHAR>(buffer.data()),
                                       static_cast<ULONG>(count), 0) < 0) {
            cleanup();
            throw UpdateError("BCrypt failed while hashing package");
        }
    }
    if (BCryptFinishHash(hash, digest.data(), static_cast<ULONG>(digest.size()), 0) < 0) {
        cleanup();
        throw UpdateError("BCrypt failed to finish SHA-256");
    }
    cleanup();
    std::ostringstream result;
    result << std::hex << std::setfill('0');
    for (const auto byte : digest) result << std::setw(2) << static_cast<int>(byte);
    return result.str();
}

void verify_job_artifact(const UpdateJob& job) {
    std::error_code error;
    const auto actual_size = std::filesystem::file_size(job.package_path, error);
    if (error || actual_size != job.file_size) throw UpdateError("package size mismatch");
    auto actual_sha = sha256_file(job.package_path);
    auto expected_sha = job.sha256;
    std::transform(expected_sha.begin(), expected_sha.end(), expected_sha.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    if (actual_sha != expected_sha) throw UpdateError("package SHA-256 mismatch");

    const auto signature = decode_base64(job.signature);
    const auto der_key = decode_base64(job.public_key);
    static constexpr std::array<unsigned char, 12> spki_prefix{
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    if (signature.size() != 64 || der_key.size() != 44 ||
        !std::equal(spki_prefix.begin(), spki_prefix.end(), der_key.begin())) {
        throw UpdateError("invalid Ed25519 signature or SubjectPublicKeyInfo key");
    }
    const std::string canonical = "PDK-ARTIFACT-V1\n" + std::to_string(job.app_id) + "\n" +
        job.target_version + "\n" + job.platform + "\n" + job.arch + "\n" +
        job.package_type + "\n" + std::to_string(job.file_size) + "\n" + expected_sha;
    if (crypto_ed25519_check(signature.data(), der_key.data() + spki_prefix.size(),
                             reinterpret_cast<const unsigned char*>(canonical.data()),
                             canonical.size()) != 0) {
        throw UpdateError("artifact Ed25519 signature verification failed");
    }
}

}  // namespace pdk
