#include "../core/global.h"
#include "../core/makedir.h"
#include "../core/config_parser.h"
#include "../core/timer.h"
#include "../dataio/sgf.h"
#include "../search/asyncbot.h"
#include "../program/setup.h"
#include "../program/playutils.h"
#include "../program/play.h"
#include "../command/commandline.h"
#include "../main.h"

#include <chrono>
#include <csignal>

using namespace std;

static std::atomic<bool> sigReceived(false);
static std::atomic<bool> shouldStop(false);
static void signalHandler(int signal)
{
  if(signal == SIGINT || signal == SIGTERM) {
    sigReceived.store(true);
    shouldStop.store(true);
  }
}

static void writeLine(
  const Search* search, const BoardHistory& baseHist,
  const vector<double>& winLossHistory, const vector<double>& scoreHistory, const vector<double>& scoreStdevHistory
) {
  const Board board = search->getRootBoard();
  int nnXLen = search->nnXLen;
  int nnYLen = search->nnYLen;

  cout << board.x_size << " ";
  cout << board.y_size << " ";
  cout << nnXLen << " ";
  cout << nnYLen << " ";
  cout << baseHist.rules.komi << " ";
  if(baseHist.isGameFinished) {
    cout << PlayerIO::playerToString(baseHist.winner) << " ";
    cout << baseHist.isResignation << " ";
    cout << baseHist.finalWhiteMinusBlackScore << " ";
  }
  else {
    cout << "-" << " ";
    cout << "false" << " ";
    cout << "0" << " ";
  }

  //Last move
  Loc moveLoc = Board::NULL_LOC;
  if(baseHist.moveHistory.size() > 0)
    moveLoc = baseHist.moveHistory[baseHist.moveHistory.size()-1].loc;
  cout << NNPos::locToPos(moveLoc,board.x_size,nnXLen,nnYLen) << " ";

  cout << baseHist.moveHistory.size() << " ";

  for(int y = 0; y<board.y_size; y++) {
    for(int x = 0; x<board.x_size; x++) {
      Loc loc = Location::getLoc(x,y,board.x_size);
      if(board.colors[loc] == C_BLACK)
        cout << "x";
      else if(board.colors[loc] == C_WHITE)
        cout << "o";
      else
        cout << ".";
    }
  }
  cout << " ";

  vector<AnalysisData> buf;
  if(!baseHist.isGameFinished) {
    int minMovesToTryToGet = 0; //just get the default number
    search->getAnalysisData(buf,minMovesToTryToGet,false,9);
  }
  cout << buf.size() << " ";
  for(int i = 0; i<buf.size(); i++) {
    const AnalysisData& data = buf[i];
    cout << NNPos::locToPos(data.move,board.x_size,nnXLen,nnYLen) << " ";
    cout << data.numVisits << " ";
    cout << data.winLossValue << " ";
    cout << data.scoreMean << " ";
    cout << data.scoreStdev << " ";
    cout << data.policyPrior << " ";
  }


  cout << winLossHistory.size() << " ";
  for(int i = 0; i<winLossHistory.size(); i++)
    cout << winLossHistory[i] << " ";
  cout << scoreHistory.size() << " ";
  assert(scoreStdevHistory.size() == scoreHistory.size());
  for(int i = 0; i<scoreHistory.size(); i++)
    cout << scoreHistory[i] << " " << scoreStdevHistory[i] << " ";

  cout << endl;
}


int MainCmds::printclockinfo(int argc, const char* const* argv) {
  (void)argc;
  (void)argv;
#ifdef OS_IS_WINDOWS
  cout << "Does nothing on windows, disabled" << endl;
#endif
#ifdef OS_IS_UNIX_OR_APPLE
  cout << "Tick unit in seconds: " << std::chrono::steady_clock::period::num << " / " <<  std::chrono::steady_clock::period::den << endl;
  cout << "Ticks since epoch: " << std::chrono::steady_clock::now().time_since_epoch().count() << endl;
#endif
  return 0;
}


int MainCmds::samplesgfs(int argc, const char* const* argv) {
  Board::initHash();
   
  Rand seedRand;

  vector<string> sgfDirs;
  string outDir;
  vector<string> excludeHashesFiles;
  double sampleProb;
  double turnWeightLambda;
  try {
    KataGoCommandLine cmd("Search for suprising good moves in sgfs");

    TCLAP::MultiArg<string> sgfDirArg("","sgfdir","Directory of sgf files",true,"DIR");
    TCLAP::ValueArg<string> outDirArg("","outdir","Directory to write results",true,string(),"DIR");
    TCLAP::MultiArg<string> excludeHashesArg("","exclude-hashes","Specify a list of hashes to filter out, one per line in a txt file",false,"FILEOF(HASH,HASH)");
    TCLAP::ValueArg<double> sampleProbArg("","sample-prob","Probability to sample each position",true,0.0,"PROB");
    TCLAP::ValueArg<double> turnWeightLambdaArg("","turn-weight-lambda","Probability to sample each position",true,0.0,"PROB");
    cmd.add(sgfDirArg);
    cmd.add(outDirArg);
    cmd.add(excludeHashesArg);
    cmd.add(sampleProbArg);
    cmd.add(turnWeightLambdaArg);
    cmd.parse(argc,argv);
    sgfDirs = sgfDirArg.getValue();
    outDir = outDirArg.getValue();
    excludeHashesFiles = excludeHashesArg.getValue();
    sampleProb = sampleProbArg.getValue();
    turnWeightLambda = turnWeightLambdaArg.getValue();
  }
  catch (TCLAP::ArgException &e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }

  Logger logger;
  logger.setLogToStdout(true);

  const string sgfSuffix = ".sgf";
  auto sgfFilter = [&sgfSuffix](const string& name) {
    return Global::isSuffix(name,sgfSuffix);
  };
  vector<string> sgfFiles;
  for(int i = 0; i<sgfDirs.size(); i++)
    Global::collectFiles(sgfDirs[i], sgfFilter, sgfFiles);
  logger.write("Found " + Global::int64ToString((int64_t)sgfFiles.size()) + " sgf files!");

  set<Hash128> excludeHashes = Sgf::readExcludes(excludeHashesFiles);
  logger.write("Loaded " + Global::uint64ToString(excludeHashes.size()) + " excludes");

  MakeDir::make(outDir);

  // ---------------------------------------------------------------------------------------------------
  ThreadSafeQueue<string*> toWriteQueue;
  auto writeLoop = [&toWriteQueue,&outDir]() {
    int fileCounter = 0;
    int numWrittenThisFile = 0;
    ofstream* out = NULL;
    while(true) {
      string* message;
      bool suc = toWriteQueue.waitPop(message);
      if(!suc)
        break;

      if(out == NULL || numWrittenThisFile > 100000) {
        if(out != NULL) {
          out->close();
          delete out;
        }
        out = new ofstream(outDir + "/" + Global::intToString(fileCounter) + ".hintposes.txt");
        fileCounter += 1;
        numWrittenThisFile = 0;
      }
      (*out) << *message << endl;
      numWrittenThisFile += 1;
      delete message;
    }

    if(out != NULL) {
      out->close();
      delete out;
    }
  };

  // ---------------------------------------------------------------------------------------------------

  //Begin writing
  std::thread writeLoopThread(writeLoop);

  // ---------------------------------------------------------------------------------------------------

  int64_t numKept = 0;
  std::set<Hash128> uniqueHashes;
  std::function<void(Sgf::PositionSample&, const BoardHistory&)> posHandler =
    [sampleProb,&toWriteQueue,turnWeightLambda,&numKept,&seedRand](Sgf::PositionSample& posSample, const BoardHistory& hist) {
    (void)hist;
    if(seedRand.nextBool(sampleProb)) {
      Sgf::PositionSample posSampleToWrite = posSample;
      int64_t startTurn = posSampleToWrite.initialTurnNumber + (int64_t)posSampleToWrite.moves.size();
      posSampleToWrite.weight = exp(-startTurn * turnWeightLambda) * posSampleToWrite.weight;
      toWriteQueue.waitPush(new string(Sgf::PositionSample::toJsonLine(posSampleToWrite)));
      numKept += 1;
    }
  };
  int64_t numExcluded = 0;
  for(size_t i = 0; i<sgfFiles.size(); i++) {
    Sgf* sgf = NULL;
    try {
      sgf = Sgf::loadFile(sgfFiles[i]);
      if(contains(excludeHashes,sgf->hash))
        numExcluded += 1;
      else
        sgf->iterAllUniquePositions(uniqueHashes, posHandler);
    }
    catch(const StringError& e) {
      logger.write("Invalid SGF " + sgfFiles[i] + ": " + e.what());
    }
    if(sgf != NULL)
      delete sgf;
  }
  logger.write("Kept " + Global::int64ToString(numKept) + " start positions");
  logger.write("Excluded " + Global::int64ToString(numExcluded) + "/" + Global::uint64ToString(sgfFiles.size()) + " sgf files");


  // ---------------------------------------------------------------------------------------------------

  toWriteQueue.setReadOnly();
  writeLoopThread.join();

  logger.write("All done");

   
  return 0;
}



//We want surprising moves that turned out not poorly
//The more surprising, the more we will weight it
static double surpriseWeight(double policyProb, Rand& rand) {
  if(policyProb < 0)
    return 0;
  double weight = 0.15 / (policyProb + 0.03) - 0.5;

  if(weight <= 0)
    return 0;
  if(weight < 0.5) {
    if(rand.nextDouble() * 0.5 >= weight)
      return 0;
    return 0.5;
  }
  return weight;
}
