#!/usr/bin/env python3
import argparse, csv, json
from collections import defaultdict
import numpy as np
from sklearn.metrics import accuracy_score, confusion_matrix, precision_recall_fscore_support, roc_auc_score, average_precision_score, roc_curve

def validate(rows):
    for key in ("speaker_id", "source_group"):
        splits=defaultdict(set)
        for row in rows: splits[row[key]].add(row["split"])
        leaked=[name for name, values in splits.items() if len(values)>1]
        if leaked: raise ValueError(f"{key} leakage across splits: {leaked[:5]}")

def metrics(rows, threshold):
    truth=np.array([int(r["label"]) for r in rows]); score=np.array([float(r["score"]) for r in rows]); pred=(score>=threshold).astype(int)
    precision,recall,f1,_=precision_recall_fscore_support(truth,pred,average="binary",zero_division=0); cm=confusion_matrix(truth,pred,labels=[0,1])
    fpr,tpr,thresholds=roc_curve(truth,score); fnr=1-tpr; index=np.nanargmin(np.abs(fnr-fpr))
    return {"n":len(rows),"threshold":threshold,"accuracy":accuracy_score(truth,pred),"precision":precision,"recall":recall,"f1":f1,"roc_auc":roc_auc_score(truth,score),"pr_auc":average_precision_score(truth,score),"false_positive_rate":cm[0,1]/max(1,cm[0].sum()),"false_negative_rate":cm[1,0]/max(1,cm[1].sum()),"eer":float((fpr[index]+fnr[index])/2),"confusion_matrix":cm.tolist()}

def main():
    p=argparse.ArgumentParser(); p.add_argument("manifest"); p.add_argument("--threshold",type=float,default=.5); args=p.parse_args()
    with open(args.manifest,newline="") as stream: rows=list(csv.DictReader(stream))
    validate(rows); test=[r for r in rows if r["split"]=="test"]
    if not test: raise SystemExit("No test rows")
    print(json.dumps(metrics(test,args.threshold),indent=2))
if __name__ == "__main__": main()
