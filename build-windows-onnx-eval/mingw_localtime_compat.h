#include <ctime>

inline std::tm* codex_localtime_r(const std::time_t* value, std::tm* result) {
  const std::tm* local = std::localtime(value);
  if(local == nullptr)
    return nullptr;
  *result = *local;
  return result;
}

#define localtime_r(value, result) codex_localtime_r((value), (result))
