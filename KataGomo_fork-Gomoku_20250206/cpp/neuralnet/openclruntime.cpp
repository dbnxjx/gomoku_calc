// Android OpenCL runtime loader.
//
// The Android NDK does not provide an OpenCL import library.  Vendor drivers
// expose the OpenCL entry points from a device-specific libOpenCL.so instead.
// These small ABI-compatible wrappers keep the rest of KataGo unchanged and
// resolve the entry points after the process starts.

#include "openclincludes.h"

#if !defined(__ANDROID__)
#error "openclruntime.cpp is only intended for Android builds"
#endif

#include <android/dlext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <cstdio>
#include <mutex>
#include <unistd.h>

#ifndef CL_PLATFORM_NOT_FOUND_KHR
// The extension error is not declared by every version of the Khronos
// header, but it is the standardized value returned by clGetPlatformIDs.
#define CL_PLATFORM_NOT_FOUND_KHR (-1001)
#endif

namespace {

void* openCLHandle = nullptr;
std::once_flag openCLLoadOnce;

using GetExportedNamespaceFn = android_namespace_t* (*)(const char*);

GetExportedNamespaceFn getExportedNamespaceFunction() {
  // android_get_exported_namespace is intentionally omitted from the public
  // NDK declarations on some releases, but Android's libdl exports it on
  // devices that expose the vendor (sphal) namespace to applications.
  static GetExportedNamespaceFn fn = []() -> GetExportedNamespaceFn {
    void* libdl = dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL);
    if(libdl != nullptr) {
      void* symbol = dlsym(libdl, "android_get_exported_namespace");
      if(symbol == nullptr)
        symbol = dlsym(libdl, "__loader_android_get_exported_namespace");
      if(symbol != nullptr)
        return reinterpret_cast<GetExportedNamespaceFn>(symbol);
    }

    void* symbol = dlsym(RTLD_DEFAULT, "android_get_exported_namespace");
    if(symbol == nullptr)
      symbol = dlsym(RTLD_DEFAULT, "__loader_android_get_exported_namespace");
    return reinterpret_cast<GetExportedNamespaceFn>(symbol);
  }();
  if(fn == nullptr)
    std::fprintf(stderr,
      "KataGo OpenCL loader: android_get_exported_namespace is unavailable.\n");
  return fn;
}

void* openWithAndroidNamespace(const char* libraryName, const char* namespaceName) {
  GetExportedNamespaceFn getNamespace = getExportedNamespaceFunction();
  if(getNamespace == nullptr)
    return nullptr;

  android_namespace_t* libraryNamespace = getNamespace(namespaceName);
  if(libraryNamespace == nullptr) {
    std::fprintf(stderr,
      "KataGo OpenCL loader: Android namespace '%s' is unavailable.\n", namespaceName);
    return nullptr;
  }

  android_dlextinfo extinfo{};
  extinfo.flags = ANDROID_DLEXT_USE_NAMESPACE;
  extinfo.library_namespace = libraryNamespace;
  return android_dlopen_ext(libraryName, RTLD_NOW | RTLD_LOCAL, &extinfo);
}

bool isUsableOpenCLHandle(void* handle) {
  return handle != nullptr && dlsym(handle, "clGetPlatformIDs") != nullptr;
}

void* openWithLibraryFd(const char* libraryName) {
  int fd = open(libraryName, O_RDONLY | O_CLOEXEC);
  if(fd < 0)
    return nullptr;

  android_dlextinfo extinfo{};
  extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
  extinfo.library_fd = fd;
  void* handle = android_dlopen_ext(libraryName, RTLD_NOW | RTLD_LOCAL, &extinfo);
  close(fd);
  return handle;
}

void loadOpenCL() {
  // Vendor OpenCL implementations normally live in Android's sphal
  // namespace. Loading through the default app namespace can fail with
  // linker warnings even when the driver is installed on the device.
  constexpr const char* libraryNames[] = {
    "libOpenCL.so",
    "libOpenCL_adreno.so",
    "libPVROCL.so",
    "libGLES_mali.so",
    "/vendor/lib64/libOpenCL.so",
    "/vendor/lib64/libOpenCL_adreno.so",
    "/vendor/lib64/libPVROCL.so",
    "/vendor/lib64/libGLES_mali.so",
    "/vendor/lib64/egl/libGLES_mali.so",
    "/system/lib64/libOpenCL.so",
    "/vendor/lib/libOpenCL.so",
    "/vendor/lib/libOpenCL_adreno.so",
    "/vendor/lib/libPVROCL.so",
    "/vendor/lib/libGLES_mali.so",
    "/vendor/lib/egl/libGLES_mali.so",
    "/system/lib/libOpenCL.so",
  };

  constexpr const char* namespaceNames[] = {
    "sphal",
    "vndk",
  };
  for(const char* namespaceName : namespaceNames) {
    for(const char* name : libraryNames) {
      void* candidate = openWithAndroidNamespace(name, namespaceName);
      if(isUsableOpenCLHandle(candidate)) {
        openCLHandle = candidate;
        return;
      }
      if(candidate != nullptr)
        dlclose(candidate);
    }
  }

  // A few vendor builds allow opening the file but reject a pathname from the
  // app linker namespace.  The fd form avoids that pathname check.  It still
  // depends on SELinux and the vendor library's own dependencies being
  // accessible, so it is intentionally only a fallback.
  for(const char* name : libraryNames) {
    if(name[0] != '/')
      continue;
    void* candidate = openWithLibraryFd(name);
    if(isUsableOpenCLHandle(candidate)) {
      openCLHandle = candidate;
      return;
    }
    if(candidate != nullptr)
      dlclose(candidate);
  }

  // Some devices make OpenCL public to the default application namespace.
  for(const char* name : libraryNames) {
    void* candidate = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if(isUsableOpenCLHandle(candidate)) {
      openCLHandle = candidate;
      return;
    }
    if(candidate != nullptr)
      dlclose(candidate);
  }

  std::fprintf(stderr,
    "KataGo OpenCL loader: no usable OpenCL runtime found; tried sphal/vndk and default namespaces.\n");
}

template<typename T>
T getOpenCLFunction(const char* name) {
  std::call_once(openCLLoadOnce, loadOpenCL);
  if(openCLHandle == nullptr)
    return nullptr;
  return reinterpret_cast<T>(dlsym(openCLHandle, name));
}

template<typename T>
T missingHandle(cl_int* error) {
  if(error != nullptr)
    *error = CL_INVALID_OPERATION;
  return nullptr;
}

cl_int missingError() {
  // CL_PLATFORM_NOT_FOUND_KHR is the useful result for the initial platform
  // query and is understood by KataGo's device enumeration code.
  return CL_PLATFORM_NOT_FOUND_KHR;
}

} // namespace

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetPlatformIDs(cl_uint num_entries, cl_platform_id* platforms, cl_uint* num_platforms) {
  using Fn = cl_int (CL_API_CALL *)(cl_uint, cl_platform_id*, cl_uint*);
  static Fn fn = getOpenCLFunction<Fn>("clGetPlatformIDs");
  return fn == nullptr ? missingError() : fn(num_entries, platforms, num_platforms);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name,
                  size_t param_value_size, void* param_value,
                  size_t* param_value_size_ret) {
  using Fn = cl_int (CL_API_CALL *)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
  static Fn fn = getOpenCLFunction<Fn>("clGetPlatformInfo");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries,
               cl_device_id* devices, cl_uint* num_devices) {
  using Fn = cl_int (CL_API_CALL *)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
  static Fn fn = getOpenCLFunction<Fn>("clGetDeviceIDs");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(platform, device_type, num_entries, devices, num_devices);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size,
                void* param_value, size_t* param_value_size_ret) {
  using Fn = cl_int (CL_API_CALL *)(cl_device_id, cl_device_info, size_t, void*, size_t*);
  static Fn fn = getOpenCLFunction<Fn>("clGetDeviceInfo");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(device, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" CL_API_ENTRY cl_context CL_API_CALL
clCreateContext(const cl_context_properties* properties, cl_uint num_devices,
                const cl_device_id* devices,
                void (CL_CALLBACK* pfn_notify)(const char*, const void*, size_t, void*),
                void* user_data, cl_int* errcode_ret) {
  using Fn = cl_context (CL_API_CALL *)(const cl_context_properties*, cl_uint, const cl_device_id*,
                                        void (CL_CALLBACK*)(const char*, const void*, size_t, void*),
                                        void*, cl_int*);
  static Fn fn = getOpenCLFunction<Fn>("clCreateContext");
  return fn == nullptr ? missingHandle<cl_context>(errcode_ret) : fn(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseContext(cl_context context) {
  using Fn = cl_int (CL_API_CALL *)(cl_context);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseContext");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(context);
}

extern "C" CL_API_ENTRY cl_command_queue CL_API_CALL
clCreateCommandQueue(cl_context context, cl_device_id device,
                     cl_command_queue_properties properties, cl_int* errcode_ret) {
  using Fn = cl_command_queue (CL_API_CALL *)(cl_context, cl_device_id, cl_command_queue_properties, cl_int*);
  static Fn fn = getOpenCLFunction<Fn>("clCreateCommandQueue");
  return fn == nullptr ? missingHandle<cl_command_queue>(errcode_ret) : fn(context, device, properties, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseCommandQueue(cl_command_queue command_queue) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseCommandQueue");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue);
}

extern "C" CL_API_ENTRY cl_mem CL_API_CALL
clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void* host_ptr, cl_int* errcode_ret) {
  using Fn = cl_mem (CL_API_CALL *)(cl_context, cl_mem_flags, size_t, void*, cl_int*);
  static Fn fn = getOpenCLFunction<Fn>("clCreateBuffer");
  return fn == nullptr ? missingHandle<cl_mem>(errcode_ret) : fn(context, flags, size, host_ptr, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseMemObject(cl_mem memobj) {
  using Fn = cl_int (CL_API_CALL *)(cl_mem);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseMemObject");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(memobj);
}

extern "C" CL_API_ENTRY cl_program CL_API_CALL
clCreateProgramWithSource(cl_context context, cl_uint count, const char** strings,
                           const size_t* lengths, cl_int* errcode_ret) {
  using Fn = cl_program (CL_API_CALL *)(cl_context, cl_uint, const char**, const size_t*, cl_int*);
  static Fn fn = getOpenCLFunction<Fn>("clCreateProgramWithSource");
  return fn == nullptr ? missingHandle<cl_program>(errcode_ret) : fn(context, count, strings, lengths, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseProgram(cl_program program) {
  using Fn = cl_int (CL_API_CALL *)(cl_program);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseProgram");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(program);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id* device_list,
               const char* options,
               void (CL_CALLBACK* pfn_notify)(cl_program, void*), void* user_data) {
  using Fn = cl_int (CL_API_CALL *)(cl_program, cl_uint, const cl_device_id*, const char*,
                                    void (CL_CALLBACK*)(cl_program, void*), void*);
  static Fn fn = getOpenCLFunction<Fn>("clBuildProgram");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(program, num_devices, device_list, options, pfn_notify, user_data);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name,
                      size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
  using Fn = cl_int (CL_API_CALL *)(cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*);
  static Fn fn = getOpenCLFunction<Fn>("clGetProgramBuildInfo");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" CL_API_ENTRY cl_kernel CL_API_CALL
clCreateKernel(cl_program program, const char* kernel_name, cl_int* errcode_ret) {
  using Fn = cl_kernel (CL_API_CALL *)(cl_program, const char*, cl_int*);
  static Fn fn = getOpenCLFunction<Fn>("clCreateKernel");
  return fn == nullptr ? missingHandle<cl_kernel>(errcode_ret) : fn(program, kernel_name, errcode_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseKernel(cl_kernel kernel) {
  using Fn = cl_int (CL_API_CALL *)(cl_kernel);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseKernel");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(kernel);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void* arg_value) {
  using Fn = cl_int (CL_API_CALL *)(cl_kernel, cl_uint, size_t, const void*);
  static Fn fn = getOpenCLFunction<Fn>("clSetKernelArg");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(kernel, arg_index, arg_size, arg_value);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clWaitForEvents(cl_uint num_events, const cl_event* event_list) {
  using Fn = cl_int (CL_API_CALL *)(cl_uint, const cl_event*);
  static Fn fn = getOpenCLFunction<Fn>("clWaitForEvents");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(num_events, event_list);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clReleaseEvent(cl_event event) {
  using Fn = cl_int (CL_API_CALL *)(cl_event);
  static Fn fn = getOpenCLFunction<Fn>("clReleaseEvent");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(event);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clGetEventProfilingInfo(cl_event event, cl_profiling_info param_name, size_t param_value_size,
                        void* param_value, size_t* param_value_size_ret) {
  using Fn = cl_int (CL_API_CALL *)(cl_event, cl_profiling_info, size_t, void*, size_t*);
  static Fn fn = getOpenCLFunction<Fn>("clGetEventProfilingInfo");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(event, param_name, param_value_size, param_value, param_value_size_ret);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clFlush(cl_command_queue command_queue) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue);
  static Fn fn = getOpenCLFunction<Fn>("clFlush");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clFinish(cl_command_queue command_queue) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue);
  static Fn fn = getOpenCLFunction<Fn>("clFinish");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read,
                    size_t offset, size_t size, void* ptr, cl_uint num_events_in_wait_list,
                    const cl_event* event_wait_list, cl_event* event) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void*, cl_uint, const cl_event*, cl_event*);
  static Fn fn = getOpenCLFunction<Fn>("clEnqueueReadBuffer");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write,
                     size_t offset, size_t size, const void* ptr, cl_uint num_events_in_wait_list,
                     const cl_event* event_wait_list, cl_event* event) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void*, cl_uint, const cl_event*, cl_event*);
  static Fn fn = getOpenCLFunction<Fn>("clEnqueueWriteBuffer");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

extern "C" CL_API_ENTRY cl_int CL_API_CALL
clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim,
                       const size_t* global_work_offset, const size_t* global_work_size,
                       const size_t* local_work_size, cl_uint num_events_in_wait_list,
                       const cl_event* event_wait_list, cl_event* event) {
  using Fn = cl_int (CL_API_CALL *)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const cl_event*, cl_event*);
  static Fn fn = getOpenCLFunction<Fn>("clEnqueueNDRangeKernel");
  return fn == nullptr ? CL_INVALID_OPERATION : fn(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event);
}
