cmake_minimum_required(VERSION 3.18)
project(hgsigner C)

add_library(hgsigner SHARED signer.c)

find_library(log-lib log)
target_link_libraries(hgsigner ${log-lib} dl)
