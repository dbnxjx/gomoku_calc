#include "../search/timecontrols.h"

#include <sstream>
#include <cmath>

TimeControls::TimeControls()
  :
   perMoveTime(10000.0),
   mainTimeLeft(1.0e10)
{}

TimeControls::~TimeControls()
{}





std::string TimeControls::toDebugString(const Board& board, const BoardHistory& hist, double lagBuffer) const {
  std::ostringstream out;
  out << "timeLeft " << mainTimeLeft;
  out << " perMoveTime " << perMoveTime;
  double minTime;
  double recommendedTime;
  double maxTime;
  getTime(board,hist,lagBuffer,minTime,recommendedTime,maxTime);
  out << " minRecMax " << minTime << " " << recommendedTime << " " << maxTime;
  return out.str();
}

std::string TimeControls::toDebugString() const {
  std::ostringstream out;
  out << "timeLeft " << mainTimeLeft;
  out << " perMoveTime " << perMoveTime;
  return out.str();
}


static double applyLagBuffer(double time, double lagBuffer) {
  if(time < 2.0 * lagBuffer)
    return time * 0.5;
  else
    return time - lagBuffer;
}

void TimeControls::getTime(const Board& board, const BoardHistory& hist, double lagBuffer, double& minTime, double& recommendedTime, double& maxTime) const {
  
  double tm = 1e10;
  double step = floor(hist.moveHistory.size() / 2);
  if(mainTimeLeft >0) tm = 0.4 * mainTimeLeft / pow(step + 10, 0.7);


  double maxTimePerMove = perMoveTime- lagBuffer;
  tm = std::min(tm, maxTimePerMove);

  if (step == 0)tm -= 1.5;//��һ�μ���̫��

  if (tm < 0)tm = 0;

  minTime = tm;
  recommendedTime = tm;
  maxTime = tm;

 // std::cout << "gettime "<<tm<<" "<< mainTimeLeft<<" "<< perMoveTime;
}
