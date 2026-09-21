#include "../core/fancymath.h"

#include <cmath>
#include <iomanip>
#include <sstream>


using namespace std;

//https://en.wikipedia.org/wiki/Beta_function
double FancyMath::beta(double a, double b) {
  return exp(lgamma(a) + lgamma(b) - lgamma(a+b));
}
double FancyMath::logbeta(double a, double b) {
  return lgamma(a) + lgamma(b) - lgamma(a+b);
}

//Modified Lentz Algorithm
static double evaluateContinuedFractionHelper(const function<double(int)>& numer, const function<double(int)>& denom, double tolerance, int maxTerms) {
  double tiny = 1e-300;
  double ret = denom(0);
  if(ret == 0.0)
    ret = tiny;
  double c = ret;
  double d = 0.0;

  int n;
  for(n = 1; n < maxTerms; n++) {
    double nextnumer = numer(n);
    double nextdenom = denom(n);
    d = nextdenom + nextnumer*d;
    if(d == 0.0)
      d = tiny;
    c = nextdenom + nextnumer/c;
    if(c == 0)
      c = tiny;
    d = 1.0/d;
    double mult = c*d;
    ret = ret * mult;
    if(fabs(mult - 1.0) <= tolerance)
      break;
  }
  return ret;
}

double FancyMath::evaluateContinuedFraction(const function<double(int)>& numer, const function<double(int)>& denom, double tolerance, int maxTerms) {
  return evaluateContinuedFractionHelper(numer,denom,tolerance,maxTerms);
}

//Textbook continued fraction term for incomplete beta function
static double incompleteBetaContinuedFraction(double x, double a, double b) {
  auto numer = [x,a,b](int n) {
    if(n % 2 == 0) {
      double m = n / 2;
      return m * (b-m) * x / (a + 2.0*m - 1.0) / (a + 2.0*m);
    }
    else {
      double m = (n-1) / 2;
      return -(a+m) * (a+b+m) * x / (a + 2.0*m) / (a + 2.0*m + 1.0);
    }
  };
  auto denom = [](int n) { (void)n; return 1.0; };
  return evaluateContinuedFractionHelper(numer, denom, 1e-15, 100000);
}

//https://en.wikipedia.org/wiki/Beta_function#Incomplete_beta_function
double FancyMath::incompleteBeta(double x, double a, double b) {
  if(!(x >= 0.0 && x <= 1.0 && a > 0.0 && b > 0.0))
    return NAN;
  if(x <= 0.0)
    return 0.0;
  if(x >= 1.0)
    return beta(a,b);
  double logx = log(x);
  double logy = log(1-x);
  if(x <= (a+1.0)/(a+b+2.0))
    return exp(logx*a + logy*b) / a / incompleteBetaContinuedFraction(x,a,b);
  else
    return beta(a,b) - (exp(logy*b + logx*a) / b / incompleteBetaContinuedFraction(1.0-x,b,a));
}

//https://en.wikipedia.org/wiki/Beta_function#Incomplete_beta_function
double FancyMath::regularizedIncompleteBeta(double x, double a, double b) {
  if(!(x >= 0.0 && x <= 1.0 && a > 0.0 && b > 0.0))
    return NAN;
  if(x <= 0.0)
    return 0.0;
  if(x >= 1.0)
    return 1.0;
  double logx = log(x);
  double logy = log(1-x);
  if(x <= (a+1.0)/(a+b+2.0))
    return exp(logx*a + logy*b - logbeta(a,b)) / a / incompleteBetaContinuedFraction(x,a,b);
  else
    return 1.0 - (exp(logy*b + logx*a - logbeta(a,b)) / b / incompleteBetaContinuedFraction(1.0-x,b,a));
}

static const double PI = 3.1415926535897932384626433832795;

double FancyMath::tdistpdf(double x, double degreesOfFreedom) {
  double v = degreesOfFreedom;
  if(!(v > 0))
    return NAN;
  return 1.0 / sqrt(v*PI) / exp(lgamma(v/2.0) - lgamma((v+1.0)/2.0)) / pow(1.0 + x*x/v, (v+1.0)/2.0);
}

double FancyMath::tdistcdf(double x, double degreesOfFreedom) {
  double v = degreesOfFreedom;
  if(!(v > 0))
    return NAN;
  if(x >= 0)
    return 1.0 - regularizedIncompleteBeta(v/(x*x+v), v/2.0, 0.5) / 2.0;
  else
    return regularizedIncompleteBeta(v/(x*x+v), v/2.0, 0.5) / 2.0;
}

double FancyMath::betapdf(double x, double a, double b) {
  if(!(x >= 0.0 && x <= 1.0 && a > 0.0 && b > 0.0))
    return NAN;
  if(x == 0.0)
    return a < 1.0 ? INFINITY : a > 1.0 ? 0.0 : 1.0/beta(a,b);
  if(x == 1.0)
    return b < 1.0 ? INFINITY : b > 1.0 ? 0.0 : 1.0/beta(a,b);
  return exp(-logbeta(a,b) + log(x)*(a-1.0) + log(1.0-x)*(b-1.0));
}

double FancyMath::betacdf(double x, double a, double b) {
  return regularizedIncompleteBeta(x,a,b);
}

double FancyMath::normToTApprox(double z, double degreesOfFreedom) {
  double n = degreesOfFreedom;
  return sqrt(n * exp(z * z * (n-1.5) / ((n-1) * (n-1))) - n);
}

#define APPROX_EQ(x,y,tolerance) testApproxEq((x),(y),(tolerance), #x, #y, __FILE__, __LINE__)
static void testApproxEq(double x, double y, double tolerance, const char* msgX, const char* msgY, const char *file, int line) {

  double maxDiff = tolerance * std::max(std::abs(x),std::max(std::abs(y),1.0));
  if(std::abs(x-y) <= maxDiff)
    return;
  Global::fatalError(
    std::string("Failed approx equal: ") + std::string(msgX) + " " + std::string(msgY) + "\n" +
    std::string("file: ") + std::string(file) + "\n" + std::string("line: ") + Global::intToString(line) + "\n" +
    std::string("Values: ") + Global::strprintf("%.17f",x) + " " + Global::strprintf("%.17f",y)
  );
}
