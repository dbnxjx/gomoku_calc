#include "../core/bsearch.h"


size_t BSearch::findFirstGt(const double* arr, double x, size_t low, size_t high) {
  if(low >= high)
    return high;
  size_t mid = (low+high)/2;
  if(arr[mid] > x)
    return findFirstGt(arr,x,low,mid);
  else
    return findFirstGt(arr,x,mid+1,high);
}

