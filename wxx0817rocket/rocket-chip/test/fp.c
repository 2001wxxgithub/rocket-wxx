#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define CACHE_LINE_SIZE 64 // Assuming cache line size is 64 bytes
#define INT_SIZE 4 // Assuming size of int is 4 bytes
#define L2_CACHE_SIZE_BYTES 128 * 1024 // Assuming L2 cache size, e.g., 16KB for demonstration
#define ELEMENTS_PER_CACHE_LINE (CACHE_LINE_SIZE / INT_SIZE)
#define ARRAY_SIZE (L2_CACHE_SIZE_BYTES / INT_SIZE) // Set array size to match L2 cache size
int main() {
    // Creating a large enough array
    int *array = (int*)malloc(ARRAY_SIZE * sizeof(int));
    float *array_fp = (float*)malloc(100 * sizeof(float));
    int test_int_mul = 0;
    double test_mul = 100.F;
    if (array == NULL) {
        perror("Malloc failed");
        return -1;
    }
    if (array_fp == NULL) {
        perror("Malloc failed");
        return -1;
    }
    array[0] = 0; // Set the first element to 0
    array[ARRAY_SIZE - 1] = ARRAY_SIZE - 1; // Set the last element to the last inde
    array_fp[0] = 0.F;
    array_fp[99] = ARRAY_SIZE - 1.1F; // Set the last element to the last inde
    // Read array elements to trigger cache misses
    int sum = 0;
    float sum_fp = 0.F;
    for (int j = 0; j < 12; j++) { // Loop 3 times as per requirement
        array[(99-j)/2] = 2;
        test_mul = array_fp[j]*1.5 + array_fp[99-j]/1.5 + test_mul;
        test_mul = array[j]*1.5 + array[99-j]/1.5 - test_mul;
        // Access the first element
        sum += array[0];
        sum_fp += array_fp[0];
        array_fp[0] = (array_fp[0]+1.2)*2.5;
        // Access the last element, ensuring a large enough distance to likely cause a cache miss
        sum += array[ARRAY_SIZE/(1+j) - 1-j];
        sum += array[ARRAY_SIZE-ARRAY_SIZE/(1+j)];
        sum += array[ARRAY_SIZE-ARRAY_SIZE/(1+j)+(1+j)*10];
        sum_fp += array_fp[99/2 -1-j];
        array_fp[99] = array_fp[99]/2.1 ;
        test_mul = array[j]*1.5 + array[99-j]/1.5 - test_mul;
        test_mul = test_mul/array_fp[5*j];
        test_int_mul = array[(99-j)/2];
    }

    printf("Sum: %d\n", sum);
    printf("Sum: %d\n", sum_fp);
    // Cleanup
    free(array);

    return 0;
}
// 运行程序: Ctrl + F5 或调试 >“开始执行(不调试)”菜单
// 调试程序: F5 或调试 >“开始调试”菜单

// 入门使用技巧: 
//   1. 使用解决方案资源管理器窗口添加/管理文件
//   2. 使用团队资源管理器窗口连接到源代码管理
//   3. 使用输出窗口查看生成输出和其他消息
//   4. 使用错误列表窗口查看错误
//   5. 转到“项目”>“添加新项”以创建新的代码文件，或转到“项目”>“添加现有项”以将现有代码文件添加到项目
//   6. 将来，若要再次打开此项目，请转到“文件”>“打开”>“项目”并选择 .sln 文件
