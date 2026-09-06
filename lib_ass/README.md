# Native libass provider

This module builds `libass.so` and publishes its patched public headers through Prefab.

Enable Prefab in the consuming Android module, add the provider AAR as a dependency, and link its imported target:

```cmake
find_package(lib_ass REQUIRED CONFIG)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE lib_ass::ass)
```

Include the public API through the exported directory:

```c
#include <ass/ass.h>
```
