#include "../core/elo.h"

#include <cmath>


using namespace std;

static const double ELO_PER_LOG_GAMMA = 173.717792761;

static double logOnePlusExpX(double x) {
  if(x >= 50)
    return 50;
  return log(1+exp(x));
}

static double logOnePlusExpXSecondDerivative(double x) {
  double halfX = 0.5 * x;
  double denom = exp(halfX) + exp(-halfX);
  return 1 / (denom * denom);
}

double ComputeElos::probWin(double eloDiff) {
  double logGammaDiff = eloDiff / ELO_PER_LOG_GAMMA;
  return 1.0 / (1.0 + exp(-logGammaDiff));
}

static double logLikelihoodOfWL(
  double eloFirstMinusSecond,
  ComputeElos::WLRecord winRecord
) {
  double logGammaFirstMinusSecond = eloFirstMinusSecond / ELO_PER_LOG_GAMMA;
  double logProbFirstWin = -logOnePlusExpX(-logGammaFirstMinusSecond);
  double logProbSecondWin = -logOnePlusExpX(logGammaFirstMinusSecond);
  return winRecord.firstWins * logProbFirstWin + winRecord.secondWins * logProbSecondWin;
}

static double logLikelihoodOfWLSecondDerivative(
  double eloFirstMinusSecond,
  ComputeElos::WLRecord winRecord
) {
  double logGammaFirstMinusSecond = eloFirstMinusSecond / ELO_PER_LOG_GAMMA;
  double logProbFirstWinSecondDerivative = -logOnePlusExpXSecondDerivative(-logGammaFirstMinusSecond);
  double logProbSecondWinSecondDerivative = -logOnePlusExpXSecondDerivative(logGammaFirstMinusSecond);
  return (winRecord.firstWins * logProbFirstWinSecondDerivative + winRecord.secondWins * logProbSecondWinSecondDerivative)
    / (ELO_PER_LOG_GAMMA * ELO_PER_LOG_GAMMA);
}

//Compute only the part of the log likelihood depending on given player
static double computeLocalLogLikelihood(
  int player,
  const vector<double>& elos,
  const ComputeElos::WLRecord* winMatrix,
  int numPlayers,
  double priorWL
) {
  double logLikelihood = 0.0;
  for(int y = 0; y<numPlayers; y++) {
    if(y == player)
      continue;
    logLikelihood += logLikelihoodOfWL(elos[player] - elos[y], winMatrix[player*numPlayers+y]);
    logLikelihood += logLikelihoodOfWL(elos[y] - elos[player], winMatrix[y*numPlayers+player]);
  }
  logLikelihood += logLikelihoodOfWL(elos[player] - 0.0, ComputeElos::WLRecord(priorWL,priorWL));

  return logLikelihood;
}

//Compute the second derivative of the log likelihood with respect to the current player
static double computeLocalLogLikelihoodSecondDerivative(
  int player,
  const vector<double>& elos,
  const ComputeElos::WLRecord* winMatrix,
  int numPlayers,
  double priorWL
) {
  double logLikelihoodSecondDerivative = 0.0;
  for(int y = 0; y<numPlayers; y++) {
    if(y == player)
      continue;
    logLikelihoodSecondDerivative += logLikelihoodOfWLSecondDerivative(elos[player] - elos[y], winMatrix[player*numPlayers+y]);
    logLikelihoodSecondDerivative += logLikelihoodOfWLSecondDerivative(elos[y] - elos[player], winMatrix[y*numPlayers+player]);
  }
  logLikelihoodSecondDerivative += logLikelihoodOfWLSecondDerivative(elos[player] - 0.0, ComputeElos::WLRecord(priorWL,priorWL));

  return logLikelihoodSecondDerivative;
}


//Approximately compute the standard deviation of all players' Elos, assuming each time that all other
//player Elos are completely confident.
vector<double> ComputeElos::computeApproxEloStdevs(
  const vector<double>& elos,
  const ComputeElos::WLRecord* winMatrix,
  int numPlayers,
  double priorWL
) {
  //Very crude - just discretely model the distribution and look at what its stdev is
  vector<double> eloStdevs(numPlayers,0.0);

  const int radius = 1500;
  vector<double> relProbs(radius*2+1,0.0);
  const double step = 1.0; //one-elo increments

  for(int player = 0; player < numPlayers; player++) {
    double logLikelihood = computeLocalLogLikelihood(player,elos,winMatrix,numPlayers,priorWL);
    double sumRelProbs = 0.0;
    vector<double> tempElos = elos;
    for(int i = 0; i < radius*2+1; i++) {
      double elo = elos[player] + (i - radius) * step;
      tempElos[player] = elo;
      double newLogLikelihood = computeLocalLogLikelihood(player,tempElos,winMatrix,numPlayers,priorWL);
      relProbs[i] = exp(newLogLikelihood-logLikelihood);
      sumRelProbs += relProbs[i];
    }

    double secondMomentAroundElo = 0.0;
    for(int i = 0; i < radius*2+1; i++) {
      double elo = elos[player] + (i - radius) * step;
      secondMomentAroundElo += relProbs[i] / sumRelProbs * (elo - elos[player]) * (elo - elos[player]);
    }
    eloStdevs[player] = sqrt(secondMomentAroundElo);
  }
  return eloStdevs;
}

//MM algorithm
/*
vector<double> ComputeElos::computeElos(
  const ComputeElos::WLRecord* winMatrix,
  int numPlayers,
  double priorWL,
  int maxIters,
  double tolerance,
  ostream* out
) {
  vector<double> logGammas(numPlayers,0.0);

  vector<double> numWins(numPlayers,0.0);
  for(int x = 0; x<numPlayers; x++) {
    for(int y = 0; y<numPlayers; y++) {
      if(x == y)
        continue;
      numWins[x] += winMatrix[x*numPlayers+y].firstWins;
      numWins[y] += winMatrix[x*numPlayers+y].secondWins;
    }
  }

  vector<double> matchLogGammaSums(numPlayers*numPlayers);
  vector<double> priorMatchLogGammaSums(numPlayers);

  auto recomputeLogGammaSums = [&]() {
    for(int x = 0; x<numPlayers; x++) {
      for(int y = 0; y<numPlayers; y++) {
        if(x == y)
          continue;
        double maxLogGamma = std::max(logGammas[x],logGammas[y]);
        matchLogGammaSums[x*numPlayers+y] = maxLogGamma + log(exp(logGammas[x] - maxLogGamma) + exp(logGammas[y] - maxLogGamma));
      }
      double maxLogGamma = std::max(logGammas[x],0.0);
      priorMatchLogGammaSums[x] = maxLogGamma + log(exp(logGammas[x] - maxLogGamma) + exp(0.0 - maxLogGamma));
    }
  };

  auto iterate = [&]() {
    recomputeLogGammaSums();

    double maxEloDiff = 0;
    for(int x = 0; x<numPlayers; x++) {
      double oldLogGamma = logGammas[x];

      double sumInvDifficulty = 0.0;
      for(int y = 0; y<numPlayers; y++) {
        if(x == y)
          continue;
        double numGamesXY = winMatrix[x*numPlayers+y].firstWins + winMatrix[x*numPlayers+y].secondWins;
        double numGamesYX = winMatrix[y*numPlayers+x].firstWins + winMatrix[y*numPlayers+x].secondWins;
        sumInvDifficulty += numGamesXY / exp(matchLogGammaSums[x*numPlayers+y] - oldLogGamma);
        sumInvDifficulty += numGamesYX / exp(matchLogGammaSums[y*numPlayers+x] - oldLogGamma);
      }
      sumInvDifficulty += priorWL / exp(priorMatchLogGammaSums[x] - oldLogGamma);
      sumInvDifficulty += priorWL / exp(priorMatchLogGammaSums[x] - oldLogGamma);

      double logGammaDiff = log((numWins[x] + priorWL) / sumInvDifficulty);
      double newLogGamma = oldLogGamma + logGammaDiff;
      logGammas[x] = newLogGamma;

      double eloDiff = ELO_PER_LOG_GAMMA * abs(logGammaDiff);
      maxEloDiff = std::max(eloDiff,maxEloDiff);
    }
    return maxEloDiff;
  };

  for(int i = 0; i<maxIters; i++) {
    double maxEloDiff = iterate();
    if(out != NULL && i % 50 == 0) {
      (*out) << "Iteration " << i << " maxEloDiff = " << maxEloDiff << endl;
    }
    if(maxEloDiff < tolerance)
      break;
  }

  vector<double> elos(numPlayers,0.0);
  for(int x = 0; x<numPlayers; x++) {
    elos[x] = ELO_PER_LOG_GAMMA * logGammas[x];
  }
  return elos;
}
*/


vector<double> ComputeElos::computeElos(
  const ComputeElos::WLRecord* winMatrix,
  int numPlayers,
  double priorWL,
  int maxIters,
  double tolerance,
  ostream* out
) {
  vector<double> elos(numPlayers,0.0);


  //General gradient-free algorithm
  vector<double> nextDelta(numPlayers,100.0);
  auto iterate = [&]() {
    double maxEloDiff = 0;
    for(int x = 0; x<numPlayers; x++) {
      double oldElo = elos[x];
      double hiElo = oldElo + nextDelta[x];
      double loElo = oldElo - nextDelta[x];

      double likelihood = computeLocalLogLikelihood(x,elos,winMatrix,numPlayers,priorWL);
      elos[x] = hiElo;
      double likelihoodHi = computeLocalLogLikelihood(x,elos,winMatrix,numPlayers,priorWL);
      elos[x] = loElo;
      double likelihoodLo = computeLocalLogLikelihood(x,elos,winMatrix,numPlayers,priorWL);

      if(likelihoodHi > likelihood) {
        elos[x] = hiElo;
        nextDelta[x] *= 1.1;
      }
      else if(likelihoodLo > likelihood) {
        elos[x] = loElo;
        nextDelta[x] *= 1.1;
      }
      else {
        elos[x] = oldElo;
        nextDelta[x] *= 0.8;
      }

      double eloDiff = nextDelta[x];
      maxEloDiff = std::max(eloDiff,maxEloDiff);
    }
    return maxEloDiff;
  };


  for(int i = 0; i<maxIters; i++) {
    double maxEloDiff = iterate();
    if(out != NULL && i % 50 == 0) {
      (*out) << "Iteration " << i << " maxEloDiff = " << maxEloDiff << endl;
    }
    if(maxEloDiff < tolerance)
      break;
  }

  return elos;
}

static bool approxEqual(double x, double y, double tolerance) {
  return std::abs(x - y) < tolerance;
}
