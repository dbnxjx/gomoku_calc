#include "../core/rand.h"
#include "../core/os.h"

#ifdef OS_IS_WINDOWS
  #include <winsock.h>
#endif
#ifdef OS_IS_UNIX_OR_APPLE
  #include <unistd.h>
#endif

#include <atomic>
#include <cstdlib>
#include <ctime>
#include <sstream>
#include <thread>

#include "../core/global.h"
#include "../core/hash.h"
#include "../core/md5.h"
#include "../core/sha2.h"
#include "../core/timer.h"
#include "../core/bsearch.h"

using namespace std;

XorShift1024Mult::XorShift1024Mult(const uint64_t* init_a)
{
  init(init_a);
}

void XorShift1024Mult::init(const uint64_t* init_a)
{
  a_idx = 0;
  for(int i = 0; i<XORMULT_LEN; i++)
    a[i] = init_a[i];
}

void XorShift1024Mult::test()
{
  const uint64_t init_a[XORMULT_LEN] = {
    15148282349006049087ULL,
    3601266951833665894ULL,
    16929445066801446424ULL,
    13475938501103070154ULL,
    15713138009143754412ULL,
    4148159782736716337ULL,
    16035594834001032141ULL,
    5555591070439871209ULL,
    4101130512537511022ULL,
    12821547636792886909ULL,
    9050874162294428797ULL,
    6187760405891629771ULL,
    10053646276519763308ULL,
    2219782655280501359ULL,
    3719698449347562208ULL,
    5421263376768154227ULL
  };

  XorShift1024Mult xorm(init_a);

  const uint32_t expected[32] = {
    0x749746d1u,
    0x9242ca14u,
    0x98db98a1u,
    0x1348e491u,
    0xde60e668u,
    0x77e37a69u,
    0xeb51a9d3u,
    0xd44b4727u,
    0x341895b0u,
    0xc7b1b3f4u,
    0xe7ef0529u,
    0x8e72ea7eu,
    0x5855da19u,
    0xfffcd2b2u,
    0xa684e430u,
    0xb76a7e0du,
    0x5af3820eu,
    0x320b0699u,
    0xdbb85ee0u,
    0xc1dcd25cu,
    0x4b395e3eu,
    0x4007756fu,
    0x76a0c667u,
    0xaa6041f6u,
    0x756f94bbu,
    0x39527d1bu,
    0x6e1232efu,
    0xb3027668u,
    0x776ea832u,
    0x35a0ed1bu,
    0x1f2f0268u,
    0xadd59669u,
  };

  for(int i = 0; i<32; i++) {
    uint32_t r = xorm.nextUInt();
    if(r != expected[i]) {
      cout << i << endl;
      cout << Global::uint32ToHexString(r) << endl;
      cout << Global::uint32ToHexString(expected[i]) << endl;
      Global::fatalError("XorShift1024Mult generated unexpected values");
    }
  }
}

//-----------------------------------------------------------------------------

PCG32::PCG32(uint64_t state)
{
  init(state);
}

void PCG32::init(uint64_t state)
{
  s = state;
}

void PCG32::test()
{
  PCG32 pcg(123);

  const uint32_t expected[16] = {
    0xb3766cbdu,
    0x65fdd305u,
    0x2a3b9b9cu,
    0x09a2dee9u,
    0x1a86aabcu,
    0x36a98234u,
    0x82e6e2b4u,
    0x10c077e5u,
    0x29755fc7u,
    0xf7fa7b5cu,
    0x1cb7ae7du,
    0xcce0e3d9u,
    0x065ec08bu,
    0x505d1cdbu,
    0x8b778f3cu,
    0xdb72f217u,
  };

  for(int i = 0; i<16; i++) {
    uint32_t r = pcg.nextUInt();
    if(r != expected[i]) {
      cout << i << endl;
      cout << Global::uint32ToHexString(r) << endl;
      cout << Global::uint32ToHexString(expected[i]) << endl;
      Global::fatalError("PCG32 generated unexpected values");
    }
  }
}

//---------------------------------------------------------------------------------

static const uint64_t zeros[XorShift1024Mult::XORMULT_LEN] = {
  0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,0ULL,
};


Rand::Rand()
  :xorm(zeros),pcg32(0ULL) //Dummy values, overridden by init
{
  init();
}

Rand::Rand(const char* seed)
  :xorm(zeros),pcg32(0ULL) //Dummy values, overridden by init
{
  init(seed);
}
Rand::Rand(const string& seed)
  :xorm(zeros),pcg32(0ULL) //Dummy values, overridden by init
{
  init(seed);
}
Rand::Rand(uint64_t seed)
  :xorm(zeros),pcg32(0ULL) //Dummy values, overridden by init
{
  init(seed);
}

Rand::~Rand()
{

}

static atomic<int> inits(0);

void Rand::init()
{
  //Assemble entropy sources

  //Atomic incrementing counter, within this run of this program
  int x = inits++;

  //Various clocks
  uint64_t time0 = (uint64_t)time(NULL);
  uint32_t clock0 = (uint32_t)clock();
  int64_t precisionTime = ClockTimer::getPrecisionSystemTime();

  string s =
    Global::intToString(x) +
    Global::uint64ToHexString(time0) +
    Global::uint32ToHexString(clock0) +
    Global::int64ToString(precisionTime);

  //Mix the hostname and pid into the seed so that starting two things on different computers almost certainly
  //pick different seeds.
#ifdef OS_IS_WINDOWS
  {
    s += "|";
    DWORD processId = GetCurrentProcessId();
    s += Global::int64ToString((int64_t)processId);
    s += "|";
    static const int bufSize = 1024;
    char hostNameBuf[bufSize];
    int result = gethostname(hostNameBuf,bufSize);
    if(result == 0)
      s += string(hostNameBuf);
  }
#endif
#ifdef OS_IS_UNIX_OR_APPLE
  {
    s += "|";
    pid_t processId = getpid();
    s += Global::int64ToString((int64_t)processId);
    s += "|";
    static const int bufSize = 1024;
    char hostNameBuf[bufSize];
    int result = gethostname(hostNameBuf,bufSize);
    if(result == 0)
      s += string(hostNameBuf);
  }
#endif

  //Mix thread id.
  {
    std::hash<std::thread::id> hashOfThread;
    size_t hash = hashOfThread(std::this_thread::get_id());
    s += "|";
    s += Global::uint64ToHexString((uint64_t)hash);
  }

  //Mix address of stack and heap
  {
    int stackVal = 0;
    int* heapVal = new int[1];
    size_t stackAddr = (size_t)(&stackVal);
    size_t heapAddr = (size_t)(heapVal);
    delete[] heapVal;
    s += "|";
    s += Global::uint64ToHexString((uint64_t)stackAddr);
    s += Global::uint64ToHexString((uint64_t)heapAddr);
  }

  //cout << s << endl;

  char hash[65];
  SHA2::get256(s.c_str(), hash);
  assert(hash[64] == '\0');
  string hashed(hash);
  init(hashed);
}

void Rand::init(uint64_t seed)
{
  init(Global::uint64ToHexString(seed));
}

void Rand::init(const char* seed)
{
  init(string(seed));
}

void Rand::init(const string& seed)
{
  initSeed = seed;

  string s;
  {
    uint32_t hash[4];
    MD5::get(seed.c_str(), seed.size(), hash);
    s += "|";
    s += std::to_string(hash[0]);
    s += "|";
    s += seed;
  }

  int counter = 0;
  int nextHashIdx = 4;
  uint64_t hash[4];
  auto getNonzero = [&s,&counter,&nextHashIdx,&hash]() -> uint64_t {
    uint64_t nextValue;
    do {
      if(nextHashIdx >= 4) {
        string tmp = std::to_string(counter) + s;
        //cout << tmp << endl;
        counter += 37;
        SHA2::get256(tmp.c_str(), hash);
        nextHashIdx = 0;
      }
      nextValue = hash[nextHashIdx];
      nextHashIdx += 1;
    } while(nextValue == 0);
    return nextValue;
  };

  uint64_t init_a[XorShift1024Mult::XORMULT_LEN];
  for(int i = 0; i<XorShift1024Mult::XORMULT_LEN; i++)
    init_a[i] = getNonzero();

  xorm.init(init_a);
  pcg32.init(getNonzero());

  hasGaussian = false;
  storedGaussian = 0.0;
  numCalls = 0;
}

size_t Rand::nextIndexCumulative(const double* cumRelProbs, size_t n)
{
  assert(n > 0);
  assert(n < 0xFFFFFFFF);
  double_t sum = cumRelProbs[n-1];
  double d = nextDouble(sum);
  size_t r = BSearch::findFirstGt(cumRelProbs,d,0,n);
  if(r == n)
    return n-1;
  return r;
}


//Marsaglia and Tsang's algorithm
double Rand::nextGamma(double a) {
  if(!(a > 0.0))
    throw StringError("Rand::nextGamma: invalid value for a: " + Global::doubleToString(a));

  if(a <= 1.0) {
    double r = nextGamma(a + 1.0);
    double inva = 1.0 / a;
    //Technically in C++, pow(0,0) could be implementation-dependent or result in an error
    //so we explicitly force the desired behavior
    double scale = inva == 0.0 ? 1.0 : pow(nextDouble(), inva);
    return r * scale;
  }

  double d = a - 1.0/3.0;
  double c = (1.0/3.0) / sqrt(d);

  while(true) {
    double x = nextGaussian();
    double vtmp = 1.0 + c * x;
    if(vtmp <= 0.0)
      continue;
    double v = vtmp * vtmp * vtmp;
    double u = nextDouble();
    double xx = x * x;
    if(u < 1.0 - 0.0331 * xx * xx)
      return d * v;
    if(u == 0.0 || log(u) < 0.5 * xx + d * (1.0 - v + log(v)))
      return d * v;
  }

  //Numeric analysis notes:
  // d >= 2/3
  // c:  0 <= 1/sqrt(9d) <= 0.4 ish
  // vtmp > 0  vtmp < some large number since gaussian can't return infinity
  // u [0,1)
  // v > 0  < some large number
  // xx >= 0 < some large number
}

