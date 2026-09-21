#!/bin/bash -eu
set -o pipefail
{
#Runs tensorflow training in $BASEDIR/train/$TRAININGNAME
#Should be run once per persistent training process.
#Outputs results in tfsavedmodels_toexport/ in an ongoing basis (EXPORTMODE == "main").
#Or, to tfsavedmodels_toexport_extra/ (EXPORTMODE == "extra").
#Or just trains without exporting (EXPORTMODE == "trainonly").

if [[ $# -lt 5 ]]
then
    echo "Usage: $0 BASEDIR TRAININGNAME MODELKIND BATCHSIZE EXPORTMODE OTHERARGS"
    echo "BASEDIR containing selfplay data and models and related directories"
    echo "TRANINGNAME name to prefix models with, specific to this training daemon"
    echo "MODELKIND what size model to train, like b10c128, see ../modelconfigs.py"
    echo "BATCHSIZE number of samples to concat together per batch for training, must match shuffle"
    echo "EXPORTMODE 'main': train and export for selfplay. 'extra': train and export extra non-selfplay model. 'trainonly': train without export"
    exit 0
fi
BASEDIR="$1"
shift
TRAININGNAME="$1"
shift
MODELKIND="$1"
shift
BATCHSIZE="$1"
shift
EXPORTMODE="$1"
shift

#------------------------------------------------------------------------------
set -x

mkdir -p "$BASEDIR"/train/"$TRAININGNAME"

if [ "$EXPORTMODE" == "main" ]
then
    EXPORT_SUBDIR=tst
    EXTRAFLAG=""
elif [ "$EXPORTMODE" == "extra" ]
then
    EXPORT_SUBDIR=tste
    EXTRAFLAG=""
elif [ "$EXPORTMODE" == "trainonly" ]
then
    EXPORT_SUBDIR=tste
    EXTRAFLAG="-no-export"
else
    echo "EXPORTMODE was not 'main' or 'extra' or 'trainonly', run with no arguments for usage"
    exit 1
fi

time python train.py \
     -traindir "$BASEDIR\\train\\$TRAININGNAME" \
     -datadir "$BASEDIR\\shuffleddata\\current\\" \
     -exportdir "$BASEDIR\\$EXPORT_SUBDIR" \
     -exportprefix "$TRAININGNAME" \
     -pos-len 15 \
     -samples-per-epoch 1000000\
     -multi-gpus 0,1\
     -batch-size "$BATCHSIZE" \
     -gpu-memory-frac 0.7 \
     -model-kind "$MODELKIND" \
     -sub-epochs 1 \
     -swa-sub-epoch-scale 1 \
     $EXTRAFLAG \
     "$@" \
     2>&1 | tee -a "$BASEDIR"/train/"$TRAININGNAME"/stdout.txt

exit 0
}

   #  -multi-gpus 0,1\