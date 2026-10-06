#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <string>
#include <vector>
#include <memory>

static void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message ? message : "Archive illisible");
}
using Archive = std::unique_ptr<archive, decltype(&archive_read_free)>;
static Archive openArchive(JNIEnv* env, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    Archive a(archive_read_new(), archive_read_free);
    archive_read_support_filter_all(a.get());
    archive_read_support_format_zip(a.get());
    archive_read_support_format_rar(a.get());
    archive_read_support_format_rar5(a.get());
    int code = archive_read_open_filename(a.get(), p, 65536);
    env->ReleaseStringUTFChars(path, p);
    if (code != ARCHIVE_OK) { fail(env, archive_error_string(a.get())); return Archive(nullptr, archive_read_free); }
    return a;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_fr_bubblebd_ArchiveBridge_entries(JNIEnv* env, jobject, jstring path) {
    auto a = openArchive(env, path); if (!a) return nullptr;
    std::vector<std::string> names;
    archive_entry* entry;
    int code;
    while ((code = archive_read_next_header(a.get(), &entry)) == ARCHIVE_OK) {
        if (archive_entry_is_encrypted(entry) > 0) { fail(env, "Archive protégée par mot de passe non prise en charge"); return nullptr; }
        if (archive_entry_filetype(entry) == AE_IFREG) {
            const char* name = archive_entry_pathname_utf8(entry);
            if (!name) name = archive_entry_pathname(entry);
            if (name) names.emplace_back(name);
        }
        if (names.size() > 10000) { fail(env, "Archive trop volumineuse : plus de 10 000 entrées"); return nullptr; }
        archive_read_data_skip(a.get());
    }
    if (code != ARCHIVE_EOF) { fail(env, archive_error_string(a.get())); return nullptr; }
    auto result = env->NewObjectArray((jsize)names.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i=0; i<names.size(); ++i) { auto s = env->NewStringUTF(names[i].c_str()); env->SetObjectArrayElement(result, (jsize)i, s); env->DeleteLocalRef(s); }
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_fr_bubblebd_ArchiveBridge_read(JNIEnv* env, jobject, jstring path, jstring name) {
    auto a = openArchive(env, path); if (!a) return nullptr;
    const char* n = env->GetStringUTFChars(name, nullptr); std::string wanted(n); env->ReleaseStringUTFChars(name, n);
    archive_entry* entry; int code;
    while ((code = archive_read_next_header(a.get(), &entry)) == ARCHIVE_OK) {
        const char* p = archive_entry_pathname_utf8(entry); if (!p) p = archive_entry_pathname(entry);
        if (p && wanted == p && archive_entry_filetype(entry) == AE_IFREG) {
            // Data is returned in memory: archive paths can never escape a directory.
            constexpr size_t LIMIT = 80 * 1024 * 1024;
            if (archive_entry_size(entry) > (long long)LIMIT) { fail(env, "Page trop volumineuse (80 Mo maximum)"); return nullptr; }
            std::vector<char> bytes; char buffer[65536]; la_ssize_t count;
            while ((count = archive_read_data(a.get(), buffer, sizeof(buffer))) > 0) {
                if (bytes.size() + count > LIMIT) { fail(env, "Page décompressée trop volumineuse"); return nullptr; }
                bytes.insert(bytes.end(), buffer, buffer + count);
            }
            if (count < 0) { fail(env, archive_error_string(a.get())); return nullptr; }
            auto result = env->NewByteArray((jsize)bytes.size());
            if(result) env->SetByteArrayRegion(result, 0, (jsize)bytes.size(), reinterpret_cast<jbyte*>(bytes.data()));
            return result;
        }
        archive_read_data_skip(a.get());
    }
    fail(env, "Page introuvable ou archive endommagée"); return nullptr;
}
