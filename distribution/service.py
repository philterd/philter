#!/usr/bin/python3

from textblob import TextBlob
from flair.models import SequenceTagger
from flair.data import Sentence
import cherrypy
import json
import os


class Span:
    def __init__(self, text, tag, score, start, end):
        self.text = text
        self.tag = tag
        self.score = score
        self.start = start
        self.end = end


class Response(object):
    c = 'none'
    d = 'none'
    p = 0
    spans = []

    def __init__(self, c, d, p, spans):
        self.c = c
        self.d = d
        self.p = p
        self.spans = spans


def obj_dict(obj):
    return obj.__dict__


def get_lens_file():
    for f in os.listdir("/opt/philter/models/"):
        if f.endswith(".lens"):
            print("Using lens file " + f)
            return "/opt/philter/models/" + f


file = get_lens_file()
model = SequenceTagger.load(file)


class PhilterModelService(object):

    @cherrypy.expose
    def process(self, c='none', d='none', p=0):

        input = cherrypy.request.body.read().decode('utf-8')

        sentences = []

        blob = TextBlob(input)
        for s in blob.sentences:
            sentences.append(Sentence(s.raw))

        model.predict(sentences)
        spans = []
        index = 0

        for i in sentences:

            start_pos = blob.sentences[index].start_index

            for entity in i.get_spans('ner'):
                if entity.tag == 'PER':
                    p1 = Span(entity.text, entity.tag, entity.score, (entity.start_position + start_pos), (entity.end_position + start_pos))
                    spans.append(p1)

            index = index + 1

        r = Response(c, d, p, spans)
        s  = json.dumps(r, default=obj_dict)

        return s

    @cherrypy.expose
    def status(self):
        return "healthy: " + file


if __name__ == '__main__':
    cherrypy.config.update({'server.socket_host': '0.0.0.0', 'server.socket_port': 18080})
    cherrypy.quickstart(PhilterModelService())