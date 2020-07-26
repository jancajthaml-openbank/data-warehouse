#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from behave import *
import os


@given('Directory {dirname} exists')
@when('Directory {dirname} exists')
def ensure_directory_existence(context, dirname):
  os.makedirs(dirname, exist_ok=True)


@given('File {filename} exists')
@when('File {filename} exists')
def ensure_file_existence(context, filename):
  ensure_directory_existence(os.path.dirname(filename))
  with open(filename, 'a'):
    os.utime(filename, None)



