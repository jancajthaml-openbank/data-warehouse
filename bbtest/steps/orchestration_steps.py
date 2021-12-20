#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from behave import *
from helpers.shell import execute
from helpers.eventually import eventually
import os
import json
import urllib.request


@given('package {package} is {operation}')
def step_impl(context, package, operation):
  if operation == 'installed':
    (code, result, error) = execute(["apt-get", "install", "-f", "-qq", "-o=Dpkg::Use-Pty=0", "-o=Dpkg::Options::=--force-confold", context.unit.binary])
    assert code == 'OK', "unable to install with code {} and {} {}".format(code, result, error)
    assert os.path.isfile('/etc/data-warehouse/conf.d/init.conf') is True, 'config file does not exists'
    execute(['systemctl', 'start', package])
  elif operation == 'uninstalled':
    (code, result, error) = execute(["apt-get", "-f", "-qq", "remove", package])
    assert code == 'OK', "unable to uninstall with code {} and {} {}".format(code, result, error)
    (code, result, error) = execute(["apt-get", "-f", "-qq", "purge", package])
    assert code == 'OK', "unable to purge with code {} and {} {}".format(code, result, error)
    assert os.path.isfile('/etc/data-warehouse/conf.d/init.conf') is False, 'config file still exists'
  else:
    assert False, 'unknown operation {}'.format(operation)


@given('systemctl contains following active units')
@then('systemctl contains following active units')
def step_impl(context):
  (code, result, error) = execute(["systemctl", "list-units", "--all", "--no-legend"])
  assert code == 'OK', str(result) + ' ' + str(error)

  items = []
  for row in context.table:
    items.append(row['name'] + '.' + row['type'])

  result = [item.replace('*', '').strip().split(' ')[0].strip() for item in result.split(os.linesep)]
  result = [item for item in result if item in items]

  assert len(result) > 0, 'units not found'


@given('systemctl does not contain following active units')
@then('systemctl does not contain following active units')
def step_impl(context):
  (code, result, error) = execute(["systemctl", "list-units", "--all", "--no-legend"])
  assert code == 'OK', str(result) + ' ' + str(error)

  items = []
  for row in context.table:
    items.append(row['name'] + '.' + row['type'])

  result = [item.replace('*', '').strip().split(' ')[0].strip() for item in result.split(os.linesep)]
  result = [item for item in result if item in items]

  assert len(result) == 0, 'units found'


@given('unit "{unit}" is running')
@then('unit "{unit}" is running')
def unit_running(context, unit):
  @eventually(10)
  def wait_for_unit_state_change():
    (code, result, error) = execute(["systemctl", "show", "-p", "SubState", unit])
    assert code == 'OK', str(result) + ' ' + str(error)
    assert 'SubState=running' in result, result

  @eventually(10)
  def wait_for_service_to_be_healthy():
    request = urllib.request.Request(method='GET', url= "http://127.0.0.1/health")
    response = urllib.request.urlopen(request, timeout=2)
    assert response.status == 200, str(response.status)
    status = json.loads(response.read().decode('utf-8'))
    assert status['healthy'] is True, 'unit is not healthy'

  wait_for_unit_state_change()
  wait_for_service_to_be_healthy()


@given('unit "{unit}" is not running')
@then('unit "{unit}" is not running')
def unit_not_running(context, unit):
  @eventually(10)
  def wait_for_unit_state_change():
    (code, result, error) = execute(["systemctl", "show", "-p", "SubState", unit])
    assert code == 'OK', str(result) + ' ' + str(error)
    assert 'SubState=dead' in result, result

  wait_for_unit_state_change()


@given('{operation} unit "{unit}"')
@when('{operation} unit "{unit}"')
def operation_unit(context, operation, unit):
  (code, result, error) = execute(["systemctl", operation, unit])
  assert code == 'OK', str(result) + ' ' + str(error)


@given('data-warehouse is configured with')
def unit_is_configured(context):
  params = dict()
  for row in context.table:
    params[row['property']] = row['value'].strip()
  context.unit.configure(params)
