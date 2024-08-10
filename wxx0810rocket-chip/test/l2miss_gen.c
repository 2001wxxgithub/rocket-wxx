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
    if (array == NULL) {
        perror("Malloc failed");
        return -1;
    }

    // Initialize array
    /*
    for (int i = 0; i < ARRAY_SIZE; i++) {
        array[i] = i;
    }*/
    // Initialize only the first and last element of the array
    array[0] = 0; // Set the first element to 0
    array[ARRAY_SIZE - 1] = ARRAY_SIZE - 1; // Set the last element to the last inde

    // Read array elements to trigger cache misses
    int sum = 0;
    for (int j = 0; j < 3; j++) { // Loop 3 times as per requirement
        // Access the first element
        sum += array[0];
        
        // Access the last element, ensuring a large enough distance to likely cause a cache miss
        sum += array[ARRAY_SIZE - 1];
    }

    printf("Sum: %d\n", sum);

    // Cleanup
    free(array);

    return 0;
}

