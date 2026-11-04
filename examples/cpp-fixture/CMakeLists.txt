cmake_minimum_required(VERSION 3.20)
project(uml_viewer_cpp_fixture LANGUAGES CXX)

add_library(cpp_fixture STATIC src/fixture.cpp)
target_include_directories(cpp_fixture PUBLIC include)
target_compile_features(cpp_fixture PUBLIC cxx_std_17)
set_target_properties(cpp_fixture PROPERTIES CXX_EXTENSIONS OFF)
