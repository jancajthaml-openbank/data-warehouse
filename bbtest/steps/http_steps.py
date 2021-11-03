#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from behave import *
import json
import time
from helpers.eventually import eventually
from helpers.http import Request


@when('I request HTTP {uri}')
def perform_http_request(context, uri):
  options = dict()
  if context.table:
    for row in context.table:
      options[row['key']] = row['value']

  context.http_request = Request(method=options['method'], url=uri)
  context.http_request.add_header('Accept', 'application/json')
  if context.text:

    if "graphql" in uri:
      payload = json.dumps({
        'query': context.text,
        'variables': None,
        'operationName': None,
      })
    else:
      payload = context.text

    context.http_request.add_header('Content-Type', 'application/json')
    context.http_request.data = payload.encode('utf-8')


@then('HTTP response is')
def check_http_response(context):
  options = dict()
  if context.table:
    for row in context.table:
      options[row['key']] = row['value']

  def diff(path, a, b):
    if type(a) == list:
      assert type(b) == list, 'types differ at {} expected: {} actual: {}'.format(path, list, type(b))
      for idx, item in enumerate(a):
        assert item in b, 'value {} was not found at {}[{}]'.format(item, path, idx)
        diff('{}[{}]'.format(path, idx), item, b[b.index(item)])
    elif type(b) == dict:
      assert type(b) == dict, 'types differ at {} expected: {} actual: {}'.format(path, dict, type(b))
      for k, v in a.items():
        assert k in b, '{} was not found in {}'.format(k, b)
        diff('{}.{}'.format(path, k), v, b[k])
    else:
      assert type(a) == type(b), 'types differ at {} expected: {} actual: {}'.format(path, type(a), type(b))
      assert a == b, 'values differ at {} expected: {} actual: {}'.format(path, a, b)

  @eventually(20)
  def wait_for_correct_response():
    http_response = dict()

    response = context.http_request.do()
    http_response['status'] = str(response.status)
    http_response['body'] = response.read().decode('utf-8')
    
    if 'status' in options:
      assert http_response['status'] == options['status'], 'expected status {} actual {}'.format(options['status'], http_response)
    if context.text:
      try:
        diff('', json.loads(context.text), json.loads(http_response['body']))
      except Exception as ex:
        raise AssertionError('error: {}, response: {}'.format(ex, http_response))

  wait_for_correct_response()
